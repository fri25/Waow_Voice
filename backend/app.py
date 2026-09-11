"""
Assistant Fon - backend FastAPI.

Pipeline (PRD 6.4) :
  /guide      : choisit la consigne audio a jouer pour un champ
  /transcrire : audio fon -> texte -> valeur (routage LLM / correspondance stricte)
                -> audio de confirmation

Modeles :
  ASR : Professor/mms-300m-fongbe (Wav2Vec2ForCTC, CPU suffisant)
  TTS : facebook/mms-tts-fon (VitsModel, CPU)
  LLM : DeepSeek (champs texte libre uniquement, jamais sur chiffres/listes fermees)

Les consignes et confirmations sont des .wav generes une fois au demarrage
puis servis en statique : latence quasi nulle, pas de traduction a la volee.
"""

import base64
import hashlib
import io
import json
import os
import re
import threading
import unicodedata
import wave
from pathlib import Path

import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

BASE = Path(__file__).resolve().parent
AUDIO_DIR = Path(os.environ.get("AUDIO_DIR", "/tmp/audio"))
AUDIO_DIR.mkdir(parents=True, exist_ok=True)

ASR_MODEL = os.environ.get("ASR_MODEL", "Professor/mms-300m-fongbe")
TTS_MODEL = os.environ.get("TTS_MODEL", "facebook/mms-tts-fon")
DEEPSEEK_KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()
DEEPSEEK_MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")
DEEPSEEK_VISION_MODEL = os.environ.get("DEEPSEEK_VISION_MODEL", "deepseek-flash")
GROQ_KEY = os.environ.get("GROQ_API_KEY", "").strip()
GROQ_MODEL = os.environ.get("GROQ_MODEL", "whisper-large-v3")
SR = 16000

# Champs dont la valeur attendue est en francais -> ASR Whisper (Groq).
CATEGORIES_FR = {"nom", "prenom", "lieu", "adresse", "profession", "email", "texte"}

PHRASES = json.loads((BASE / "phrases_fon.json").read_text(encoding="utf-8"))

# Types a vocabulaire ferme -> jamais de LLM (PRD 6.1).
CATEGORIES_FERMEES = {"oui_non", "sexe", "matrimonial"}
# Types numeriques -> dictee chiffre par chiffre + concatenation des .wav.
CATEGORIES_NUMERIQUES = {
    "telephone", "cnss", "piece", "nombre", "date_jour", "date_annee", "date_mois",
}

app = FastAPI(title="Assistant Fon", version="1.0")

_asr = None
_tts = None
_tts_tok = None
_verrou = threading.Lock()
_audio_pret = threading.Event()

try:
    import torch  # noqa: F401
    _TORCH_OK = True
except Exception:  # pragma: no cover
    _TORCH_OK = False


# --------------------------------------------------------------------- utilitaires

def _texte_phrase(noeud: dict) -> str:
    return (noeud.get("fon") or noeud.get("fr") or "").strip()


def _champ_phrase(type_champ: str) -> dict:
    return PHRASES["champs"].get(type_champ) or PHRASES["champs"]["texte"]


def _slug(texte: str) -> str:
    t = unicodedata.normalize("NFD", texte.lower())
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    t = re.sub(r"[^a-z0-9]+", "_", t).strip("_")
    return t[:40] or "audio"


def _nettoyer_pour_tts(texte: str) -> str:
    """Garde uniquement les caracteres presents dans le vocabulaire du tokenizer."""
    texte = texte.lower()
    vocab = set(_tts_tok.get_vocab().keys())
    sortie = "".join(c if c in vocab else " " for c in texte)
    return re.sub(r"\s+", " ", sortie).strip()


# ------------------------------------------------------------------------- modeles

def charger_asr():
    global _asr
    if _asr is None:
        with _verrou:
            if _asr is None:
                from transformers import pipeline
                _asr = pipeline("automatic-speech-recognition", model=ASR_MODEL)
    return _asr


def transcrire_groq(donnees: bytes):
    """ASR Whisper via l'API Groq : rapide et bon sur le francais / les accents."""
    if not GROQ_KEY:
        return None
    try:
        from openai import OpenAI
        client = OpenAI(api_key=GROQ_KEY, base_url="https://api.groq.com/openai/v1")
        resultat = client.audio.transcriptions.create(
            model=GROQ_MODEL,
            file=("audio.wav", donnees),
            response_format="text",
        )
        texte = resultat if isinstance(resultat, str) else getattr(resultat, "text", "")
        return (texte or "").strip() or None
    except Exception as exc:  # pragma: no cover
        print(f"[Groq] echec : {exc}")
        return None


def transcrire_mms(donnees: bytes):
    """ASR fon (MMS) sur les octets audio ; None si echec."""
    try:
        echantillons, sr = lire_wav(donnees)
    except Exception as exc:
        print(f"[MMS] audio illisible : {exc}")
        return None
    try:
        asr = charger_asr()
        texte = asr({"array": echantillons, "sampling_rate": sr}).get("text", "")
        return (texte or "").strip() or None
    except Exception as exc:
        print(f"[MMS] echec : {exc}")
        return None


def charger_tts():
    global _tts, _tts_tok
    if _tts is None:
        with _verrou:
            if _tts is None:
                from transformers import AutoTokenizer, VitsModel
                _tts_tok = AutoTokenizer.from_pretrained(TTS_MODEL)
                _tts = VitsModel.from_pretrained(TTS_MODEL)
    return _tts


def synthetiser(texte: str) -> np.ndarray:
    charger_tts()
    propre = _nettoyer_pour_tts(texte)
    if not propre:
        return np.zeros(SR // 2, dtype="float32")
    import torch
    torch.manual_seed(555)
    entrees = _tts_tok(propre, return_tensors="pt")
    with torch.no_grad():
        onde = _tts(**entrees).waveform
    return onde.squeeze().cpu().numpy().astype("float32")


# ---------------------------------------------------------------------------- wav

def ecrire_wav(echantillons: np.ndarray, chemin: Path) -> None:
    pcm = np.clip(echantillons, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(str(chemin), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def lire_wav(donnees: bytes):
    with wave.open(io.BytesIO(donnees), "rb") as w:
        sr = w.getframerate()
        canaux = w.getnchannels()
        largeur = w.getsampwidth()
        brut = w.readframes(w.getnframes())

    if largeur == 2:
        arr = np.frombuffer(brut, dtype="<i2").astype("float32") / 32768.0
    elif largeur == 4:
        arr = np.frombuffer(brut, dtype="<i4").astype("float32") / 2147483648.0
    elif largeur == 1:
        arr = (np.frombuffer(brut, dtype="uint8").astype("float32") - 128.0) / 128.0
    else:
        raise ValueError(f"largeur d'echantillon non supportee : {largeur}")

    if canaux > 1:
        arr = arr.reshape(-1, canaux).mean(axis=1)

    if sr != SR and len(arr) > 1:
        n = int(len(arr) * SR / sr)
        arr = np.interp(np.linspace(0, len(arr), n, endpoint=False),
                        np.arange(len(arr)), arr).astype("float32")
        sr = SR

    return arr, sr


def concatener(chemins, sortie: Path) -> None:
    morceaux = []
    silence = np.zeros(int(SR * 0.15), dtype="float32")
    for c in chemins:
        with wave.open(str(c), "rb") as w:
            arr = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype("float32") / 32768.0
        morceaux.append(arr)
        morceaux.append(silence)
    ecrire_wav(np.concatenate(morceaux) if morceaux else silence, sortie)


def _fichier(nom: str, texte: str) -> Path:
    chemin = AUDIO_DIR / f"{nom}.wav"
    if not chemin.exists():
        ecrire_wav(synthetiser(texte), chemin)
    return chemin


def fichier_champ(type_champ: str) -> Path:
    return _fichier(f"consigne_{type_champ}", _texte_phrase(_champ_phrase(type_champ)))


def fichier_sys(cle: str) -> Path:
    return _fichier(f"sys_{cle}", _texte_phrase(PHRASES["systeme"][cle]))


def fichier_chiffre(chiffre: str) -> Path:
    return _fichier(f"chiffre_{chiffre}", _texte_phrase(PHRASES["chiffres"][chiffre]))


def generer_confirmation(valeur: str, categorie: str) -> str:
    morceaux = [fichier_sys("jai_ecrit")]
    if categorie in CATEGORIES_NUMERIQUES and valeur.isdigit():
        morceaux += [fichier_chiffre(d) for d in valeur]
    else:
        chemin_valeur = _fichier(f"val_{_slug(valeur)}", valeur)
        morceaux.append(chemin_valeur)
    morceaux.append(fichier_sys("est_bon"))

    sortie = AUDIO_DIR / f"confirm_{_slug(valeur)}.wav"
    concatener(morceaux, sortie)
    return f"/audio/{sortie.name}"


# ------------------------------------------------------------------ routage valeur

# Le modele peut sortir des caracteres visuellement proches (epsilon grec, etc.)
# qu'il faut ramener aux lettres fon avant de comparer.
_CONFUSABLES = str.maketrans({
    "ε": "ɛ", "Ε": "ɛ",
    "ο": "ɔ", "Ο": "ɔ",
    "đ": "ɖ", "Đ": "ɖ",
    "ɩ": "i",
})

# Seuil de tolerance pour la correspondance stricte (mots courts).
SEUIL_STRICT = 0.4


def _normaliser(s: str) -> str:
    s = unicodedata.normalize("NFD", (s or "").lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = s.translate(_CONFUSABLES)
    return re.sub(r"[^\w]+", "", s)


def _levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    precedente = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        courante = [i]
        for j, cb in enumerate(b, 1):
            cout = 0 if ca == cb else 1
            courante.append(min(courante[j - 1] + 1, precedente[j] + 1, precedente[j - 1] + cout))
        precedente = courante
    return precedente[-1]


def correspondance_stricte(texte_asr: str, categorie: str):
    """Renvoie (valeur, ratio) ; ratio proche de 0 = bonne correspondance."""
    options = PHRASES["vocabulaire"].get(categorie, [])
    cible = _normaliser(texte_asr)
    meilleur, meilleur_ratio = None, 1.0
    for opt in options:
        for forme in {opt.get("fon", ""), opt.get("fr", "")}:
            ref = _normaliser(forme)
            if not ref:
                continue
            ratio = _levenshtein(cible, ref) / max(1, len(ref))
            if ratio < meilleur_ratio:
                meilleur, meilleur_ratio = opt, ratio
    return meilleur, meilleur_ratio


def transcrire_chiffres(texte_asr: str):
    """Dictee chiffre par chiffre : chaque mot -> un chiffre, sinon None (PRD 6.1)."""
    mots = [m for m in re.split(r"\s+", (texte_asr or "").strip()) if m]
    if not mots:
        return None
    chiffres = []
    for mot in mots:
        cible = _normaliser(mot)
        meilleur, meilleur_ratio = None, 1.0
        for chiffre, noeud in PHRASES["chiffres"].items():
            for forme in {noeud.get("fon", ""), noeud.get("fr", "")}:
                ref = _normaliser(forme)
                if not ref:
                    continue
                ratio = _levenshtein(cible, ref) / max(1, len(ref))
                if ratio < meilleur_ratio:
                    meilleur, meilleur_ratio = chiffre, ratio
        if meilleur is None or meilleur_ratio > SEUIL_STRICT:
            return None
        chiffres.append(meilleur)
    return "".join(chiffres) if chiffres else None


def normaliser_llm(texte_fon: str, type_champ: str):
    if not DEEPSEEK_KEY:
        return None
    try:
        from openai import OpenAI
        client = OpenAI(api_key=DEEPSEEK_KEY, base_url="https://api.deepseek.com")
        reponse = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            temperature=0,
            messages=[
                {
                    "role": "system",
                    "content": (
                        "Tu recois une transcription approximative, ecrite en orthographe fon, "
                        "d'un mot ou d'une courte phrase prononcee pour un champ de formulaire "
                        "administratif beninois. Le type du champ est indique. Reconstitue la "
                        "valeur attendue en francais, avec l'orthographe la plus plausible "
                        "(par exemple un nom beninois ou francais ecrit correctement). Si le champ "
                        "attend un nom propre, ecris le nom propre francais correspondant. "
                        "Reponds UNIQUEMENT par la valeur, sans phrase ni commentaire. "
                        "Ne reponds INCOMPRIS que si la transcription est vide ou clairement du bruit."
                    ),
                },
                {"role": "user", "content": f"Champ: {type_champ}\nTranscription fon: {texte_fon}"},
            ],
        )
        return (reponse.choices[0].message.content or "").strip()
    except Exception as exc:  # pragma: no cover
        print(f"[LLM] echec : {exc}")
        return None


# ------------------------------------------------------------------ vision ecran

def _exemples_champs() -> str:
    """Quelques correspondances validees (label FR -> consigne fon) pour amorcer le modele."""
    lignes = []
    for cle, noeud in PHRASES["champs"].items():
        fr = (noeud.get("fr") or "").strip()
        fon = (noeud.get("fon") or "").strip()
        if fr and fon:
            lignes.append(f'- "{fr}" (type {cle}) -> {fon}')
    return "\n".join(lignes)


PROMPT_VISION = (
    "Tu es l'assistant vocal d'une personne qui ne lit pas le francais et ne "
    "sait pas utiliser le clavier. On te donne la capture d'ecran d'un ecran "
    "quelconque (application, site, formulaire).\n"
    "1) Repere la zone ou le champ que la personne doit remplir maintenant.\n"
    "2) Choisis son type parmi : nom, prenom, date_jour, date_mois, date_annee, "
    "date, lieu, telephone, adresse, profession, cnss, piece, sexe, matrimonial, "
    "email, nombre, texte.\n"
    "3) Ecris la phrase a dire a l'oral en fon, courte et naturelle, jamais en "
    "francais, dans le style des exemples.\n"
    "Reponds UNIQUEMENT par un objet JSON avec les cles : "
    '"description" (une phrase en francais), "champ" (le libelle lu a l\'ecran), '
    '"categorie" (un des types), "fon" (la phrase en fon).\n\n'
    "Exemples de style (libelle en francais, consigne en fon) :\n"
    + _exemples_champs()
)


def _extraire_json(texte: str):
    """Recupere le premier objet JSON, meme entoure de ``` ou de texte."""
    if not texte:
        return None
    nettoye = re.sub(r"```(?:json)?", "", texte).strip("` \n")
    debut = nettoye.find("{")
    fin = nettoye.rfind("}")
    if debut < 0 or fin <= debut:
        return None
    try:
        return json.loads(nettoye[debut:fin + 1])
    except Exception:
        return None


def analyser_ecran(image: bytes):
    """Capture d'ecran -> {description, champ, categorie, fon} via DeepSeek vision."""
    if not DEEPSEEK_KEY:
        return None
    try:
        from openai import OpenAI
        client = OpenAI(api_key=DEEPSEEK_KEY, base_url="https://api.deepseek.com")
        b64 = base64.b64encode(image).decode("ascii")
        reponse = client.chat.completions.create(
            model=DEEPSEEK_VISION_MODEL,
            temperature=0,
            max_tokens=800,
            extra_body={"thinking": {"type": "disabled"}},
            messages=[
                {"role": "system", "content": PROMPT_VISION},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Voici la capture d'ecran. Applique la consigne."},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
                    ],
                },
            ],
        )
        contenu = (reponse.choices[0].message.content or "").strip()
        donnees = _extraire_json(contenu)
        if donnees and (donnees.get("fon") or "").strip():
            return donnees
        print(f"[Vision] reponse inattendue : {contenu[:200]}")
        return None
    except Exception as exc:  # pragma: no cover
        print(f"[Vision] echec : {exc}")
        return None


# ------------------------------------------------------------------------- demarrage

def preparer_audio():
    charger_tts()
    for nom in PHRASES["champs"]:
        fichier_champ(nom)
    for cle in PHRASES["systeme"]:
        fichier_sys(cle)
    for chiffre in PHRASES["chiffres"]:
        fichier_chiffre(chiffre)
    _audio_pret.set()
    print("[startup] audio pret", flush=True)


@app.on_event("startup")
def _au_demarrage():
    threading.Thread(target=preparer_audio, daemon=True).start()


# ------------------------------------------------------------------------- routes

@app.get("/")
def racine():
    return {"service": "assistant-fon", "endpoints": ["/guide", "/comprendre", "/transcrire", "/sante"]}


@app.get("/sante")
def sante():
    return {
        "ok": True,
        "audio_pret": _audio_pret.is_set(),
        "asr_charge": _asr is not None,
        "asr_groq": bool(GROQ_KEY),
        "llm_actif": bool(DEEPSEEK_KEY),
        "modeles": {"asr": ASR_MODEL, "tts": TTS_MODEL},
    }


@app.post("/guide")
def guide(payload: dict):
    champs = payload.get("champs") or []
    index = int(payload.get("index_courant", 0))
    type_champ = "texte"
    if 0 <= index < len(champs):
        type_champ = champs[index].get("type") or "texte"
    if type_champ not in PHRASES["champs"]:
        type_champ = "texte"

    if type_champ in CATEGORIES_NUMERIQUES:
        mode = "chiffres"
    elif type_champ in CATEGORIES_FERMEES:
        mode = "choix"
    else:
        mode = "libre"

    _audio_pret.wait(timeout=120)
    fichier = fichier_champ(type_champ)
    return {"categorie": type_champ, "audio_url": f"/audio/{fichier.name}", "mode_saisie": mode}


@app.post("/comprendre")
async def comprendre(image: UploadFile = File(...)):
    """Usage general : capture d'ecran -> que dit l'assistant (en fon)."""
    donnees = await image.read()
    analyse = analyser_ecran(donnees)

    if not analyse:
        _audio_pret.wait(timeout=60)
        return {
            "statut": "incompris",
            "description": None,
            "champ": None,
            "categorie": "texte",
            "fon": None,
            "audio_url": "/audio/sys_incompris.wav",
        }

    fon = (analyse.get("fon") or "").strip()
    categorie = analyse.get("categorie") or "texte"
    if categorie not in PHRASES["champs"]:
        categorie = "texte"

    _audio_pret.wait(timeout=120)
    empreinte = hashlib.md5(fon.encode("utf-8")).hexdigest()[:12]
    chemin = _fichier(f"vision_{empreinte}", fon)

    return {
        "statut": "ok",
        "description": analyse.get("description"),
        "champ": analyse.get("champ"),
        "categorie": categorie,
        "fon": fon,
        "audio_url": f"/audio/{chemin.name}",
    }


@app.post("/transcrire")
async def transcrire(audio: UploadFile = File(...), categorie: str = Form("texte")):
    donnees = await audio.read()

    # On collecte les transcriptions candidates : Whisper (francais) et MMS (fon).
    candidats = []  # liste de (texte, source)

    if categorie in CATEGORIES_FR:
        if GROQ_KEY:
            t = transcrire_groq(donnees)
            if t:
                candidats.append((t, "groq"))
        if not candidats:
            t = transcrire_mms(donnees)
            if t:
                candidats.append((t, "mms"))
    else:
        # Champs fon : on accepte une reponse en fon (MMS) OU en francais (Whisper).
        if GROQ_KEY:
            t = transcrire_groq(donnees)
            if t:
                candidats.append((t, "groq"))
        t = transcrire_mms(donnees)
        if t:
            candidats.append((t, "mms"))

    if not candidats:
        return JSONResponse({"statut": "incompris", "erreur": "asr"}, status_code=500)

    transcription, source = candidats[0]
    valeur = None
    statut = "incompris"

    if categorie in CATEGORIES_FERMEES:
        for texte, src in candidats:
            option, ratio = correspondance_stricte(texte, categorie)
            if option is not None and ratio <= SEUIL_STRICT:
                transcription, source = texte, src
                valeur, statut = option.get("valeur"), "ok"
                break
    elif categorie in CATEGORIES_NUMERIQUES:
        for texte, src in candidats:
            chiffres = transcrire_chiffres(texte)
            if chiffres:
                transcription, source = texte, src
                valeur, statut = chiffres, "ok"
                break
    else:
        valeur_llm = normaliser_llm(transcription, categorie)
        if valeur_llm and valeur_llm != "INCOMPRIS":
            valeur, statut = valeur_llm, "ok"
        elif transcription and categorie in {"nom", "prenom", "lieu", "texte", "email"}:
            # Repli : on ecrit ce que l'ASR a entendu plutot que de bloquer la boucle.
            valeur, statut = transcription, "ok"

    reponse = {"transcription": transcription, "source": source, "valeur": valeur,
               "statut": statut, "audio_confirmation_url": None}

    if statut == "ok" and valeur:
        _audio_pret.wait(timeout=60)
        reponse["audio_confirmation_url"] = generer_confirmation(valeur, categorie)
    else:
        _audio_pret.wait(timeout=60)
        reponse["audio_confirmation_url"] = f"/audio/sys_incompris.wav"

    return reponse


app.mount("/audio", StaticFiles(directory=str(AUDIO_DIR)), name="audio")


if __name__ == "__main__":  # pragma: no cover
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "7860")))
