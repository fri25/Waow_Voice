# Assistant Fon - backend servi depuis un kernel Kaggle (GPU T4 + tunnel Cloudflare).
#
# Reglages du kernel : Accelerator = GPU T4 x2, Internet = On, type "script"
# (il continue de tourner navigateur ferme, jusqu'a ~12 h).
#
# Secrets Kaggle (Add-ons > Secrets) :
#   DEEPSEEK_API_KEY  obligatoire (vision + noms propres)
#   API_TOKEN         facultatif  : si defini, l'app doit envoyer le meme
#                                   jeton dans l'en-tete X-Token
#   GROQ_API_KEY      facultatif  : Whisper pour les champs francais
#
# On reutilise app.py tel quel (telecharge depuis le depot HF) et on remplace
# l'ASR par GRIOT-ASR (fon) et la normalisation par Griot-MT, sauf pour les
# noms propres qui restent chez DeepSeek.
#
# Push : kaggle kernels push -p backend/kaggle --accelerator NvidiaTeslaT4
# L'URL du tunnel s'affiche dans le log du kernel (elle change a chaque relance).

import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import urllib.request

HF_REPO = "octavebahoun/assistant-fon-backend"
DOSSIER = "/kaggle/working/backend"
AUDIO_DIR = "/kaggle/working/audio"
PORT = 7860
DUREE_MAX = 11 * 3600  # on s'arrete avant la coupure Kaggle (12 h)

# ------------------------------------------------------------------ dependances

subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", "torchao"], check=False)
subprocess.run(
    [
        sys.executable, "-m", "pip", "install", "-q",
        "transformers==5.16.1", "peft==0.20.0", "accelerate==1.14.0",
        "sentencepiece", "librosa", "soundfile",
        "fastapi", "uvicorn", "python-multipart", "openai", "huggingface_hub",
    ],
    check=True,
)
print("deps ok", flush=True)

# ----------------------------------------------------------------------- secrets


def secret(nom: str) -> str:
    """Secret Kaggle, sinon variable d'environnement, sinon chaine vide."""
    try:
        from kaggle_secrets import UserSecretsClient
        return (UserSecretsClient().get_secret(nom) or "").strip()
    except Exception:
        return os.environ.get(nom, "").strip()


os.environ["DEEPSEEK_API_KEY"] = secret("DEEPSEEK_API_KEY")
os.environ["API_TOKEN"] = secret("API_TOKEN")
os.environ["GROQ_API_KEY"] = secret("GROQ_API_KEY")
os.environ["AUDIO_DIR"] = AUDIO_DIR
print(
    "secrets : deepseek=%s jeton=%s groq=%s"
    % (
        bool(os.environ["DEEPSEEK_API_KEY"]),
        bool(os.environ["API_TOKEN"]),
        bool(os.environ["GROQ_API_KEY"]),
    ),
    flush=True,
)

# ------------------------------------------------------------------------ modeles

import torch  # noqa: E402
from huggingface_hub import hf_hub_download  # noqa: E402
from peft import PeftModel  # noqa: E402
from transformers import (  # noqa: E402
    AutoModelForSeq2SeqLM,
    AutoModelForSpeechSeq2Seq,
    AutoTokenizer,
    WhisperFeatureExtractor,
    WhisperProcessor,
    WhisperTokenizer,
)

DEV = "cuda" if torch.cuda.is_available() else "cpu"
DT = torch.float16 if DEV == "cuda" else torch.float32
print(f"device={DEV} dtype={DT}", flush=True)

ASR = "bivariant/griot-asr"
fe = WhisperFeatureExtractor.from_pretrained(ASR)
asr_model = (
    AutoModelForSpeechSeq2Seq.from_pretrained(
        ASR, trust_remote_code=True, language="fon", dtype=DT
    )
    .to(DEV)
    .eval()
)
asr_proc = WhisperProcessor(
    feature_extractor=fe,
    tokenizer=WhisperTokenizer.from_pretrained(ASR, subfolder="fon"),
)

MT = "bivariant/griot-mt"
mt_tok = AutoTokenizer.from_pretrained(MT)
mt = (
    PeftModel.from_pretrained(
        AutoModelForSeq2SeqLM.from_pretrained(MT, dtype=DT).to(DEV).eval(),
        MT,
        subfolder="adapters/fon",
    )
    .to(DEV)
    .eval()
)
print("modeles GRIOT charges", flush=True)

# -------------------------------------------------------------- backend app.py

os.makedirs(DOSSIER, exist_ok=True)
for fichier in ["app.py", "phrases_fon.json"]:
    chemin = hf_hub_download(repo_id=HF_REPO, filename=fichier, repo_type="dataset")
    shutil.copy(chemin, os.path.join(DOSSIER, fichier))
sys.path.insert(0, DOSSIER)
import app as backend  # noqa: E402

normaliseur_deepseek = backend.normaliser_llm

_REPONSE_META = re.compile(
    r"incompris|comprends|comprend pas|compris|d[eé]sol[eé]|je ne |"
    r"pas entendu|r[eé]p[eè]te|bruit|noise|hors sujet",
    re.IGNORECASE,
)


def est_meta(valeur: str) -> bool:
    v = (valeur or "").strip()
    return (not v) or len(v) > 60 or bool(_REPONSE_META.search(v))


def traduire(texte, source, cible):
    if not texte:
        return None
    try:
        mt_tok.src_lang = source
        entrees = mt_tok(texte, return_tensors="pt", truncation=True).to(DEV)
        with torch.inference_mode():
            sortie = mt.generate(
                **entrees,
                forced_bos_token_id=mt_tok.convert_tokens_to_ids(cible),
                num_beams=4,
                max_length=257,
                repetition_penalty=1.3,
                no_repeat_ngram_size=3,
            )
        return mt_tok.decode(sortie[0], skip_special_tokens=True).strip()
    except Exception:
        print("[MT] " + traceback.format_exc(), flush=True)
        return None


def asr_griot(donnees):
    """Remplace l'ASR MMS d'app.py : memes octets WAV en entree, texte en sortie."""
    try:
        arr, _ = backend.lire_wav(donnees)
        entree = asr_proc(arr, sampling_rate=16000, return_tensors="pt")[
            "input_features"
        ].to(DEV, DT)
        with torch.inference_mode():
            ids = asr_model.generate(entree)
        texte = asr_proc.batch_decode(ids, skip_special_tokens=True)[0].strip()
        print("[GRIOT-ASR]", repr(texte), flush=True)
        return texte or None
    except Exception:
        print("[ASR] " + traceback.format_exc(), flush=True)
        return None


def llm_hybride(texte_fon, type_champ):
    """Noms propres -> DeepSeek (jamais traduits) ; le reste -> Griot-MT."""
    try:
        if type_champ in {"nom", "prenom", "lieu"}:
            return normaliseur_deepseek(texte_fon, type_champ)
        resultat = traduire(texte_fon, "fon_Latn", "fra_Latn")
        print(f"[GRIOT-MT] {texte_fon!r} -> {resultat!r}", flush=True)
        if resultat and not est_meta(resultat):
            return resultat
        return normaliseur_deepseek(texte_fon, type_champ)
    except Exception:
        print("[HYB] " + traceback.format_exc(), flush=True)
        return None


backend.transcrire_mms = asr_griot
backend.normaliser_llm = llm_hybride

# --------------------------------------------------------------------- serveur

import uvicorn  # noqa: E402

serveur = threading.Thread(
    target=lambda: uvicorn.run(backend.app, host="0.0.0.0", port=PORT, log_level="info"),
    daemon=True,
)
serveur.start()

for _ in range(60):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/sante", timeout=2) as r:
            print("sante :", r.read().decode(), flush=True)
            break
    except Exception:
        time.sleep(2)
else:
    raise SystemExit("le serveur n'a pas demarre")

# ---------------------------------------------------------------------- tunnel

BIN = "/kaggle/working/cloudflared"
if not os.path.exists(BIN):
    urllib.request.urlretrieve(
        "https://github.com/cloudflare/cloudflared/releases/latest/download/"
        "cloudflared-linux-amd64",
        BIN,
    )
    os.chmod(BIN, 0o755)

tunnel = subprocess.Popen(
    [BIN, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{PORT}"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
)

url = None
debut = time.time()
while url is None and time.time() - debut < 120:
    ligne = tunnel.stdout.readline()
    if not ligne:
        break
    trouve = re.search(r"https://[-\w]+\.trycloudflare\.com", ligne)
    if trouve:
        url = trouve.group(0)

if not url:
    raise SystemExit("tunnel Cloudflare indisponible")

print("\n" + "=" * 60, flush=True)
print("URL DU BACKEND :", url, flush=True)
print("A coller dans l'app, ecran Serveur > Adresse du backend", flush=True)
print("=" * 60 + "\n", flush=True)

# On garde le kernel vivant et on reaffiche l'URL : elle est facile a perdre
# dans un log de plusieurs heures.
debut = time.time()
while time.time() - debut < DUREE_MAX:
    time.sleep(300)
    ecoule = int((time.time() - debut) / 60)
    print(f"[{ecoule} min] en ligne : {url}", flush=True)

tunnel.terminate()
print("duree maximale atteinte, arret propre", flush=True)
