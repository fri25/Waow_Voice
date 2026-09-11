---
license: mit
---

# Assistant Fon — backend

Backend FastAPI pour l'app Android **Assistant Fon** : guide vocale en fon pour
remplir des formulaires administratifs.

## Pipeline

```
/guide       -> consigne audio (.wav pre-genere) selon le type de champ
/transcrire  -> audio fon -> ASR -> routage -> valeur + audio de confirmation
```

- **ASR** : `Professor/mms-300m-fongbe` (Wav2Vec2ForCTC, CPU)
- **TTS** : `facebook/mms-tts-fon` (VitsModel, CPU)
- **LLM** : DeepSeek, uniquement pour les champs a texte libre (jamais sur les
  chiffres ni les listes fermees — voir PRD 6.1)

## Contrat d'API

```
POST /guide
{ "champs": [ {"index":0,"label":"Nom","type":"nom"} ], "index_courant": 0 }
-> { "categorie": "nom", "audio_url": "/audio/consigne_nom.wav", "mode_saisie": "libre" }

POST /transcrire   (multipart: audio=<wav 16kHz mono>, categorie=telephone)
-> { "valeur": "97453210", "statut": "ok"|"incompris",
     "audio_confirmation_url": "/audio/confirm_97453210.wav" }

GET /sante
```

## Variables d'environnement

| Variable | Defaut | Role |
|---|---|---|
| `ASR_MODEL` | `Professor/mms-300m-fongbe` | modele de transcription |
| `TTS_MODEL` | `facebook/mms-tts-fon` | synthese vocale |
| `DEEPSEEK_API_KEY` | (vide) | active la normalisation LLM |
| `AUDIO_DIR` | `/tmp/audio` | dossier des wav generes |

## A remplir

- `phrases_fon.json` : les champs `fon` sont vides. A remplir et **valider par
  un locuteur natif**. Tant qu'ils sont vides, le backend utilise le francais.
- `vocabulaire` (oui/non, sexe, situation) : mots fon attendus par l'ASR, pour
  la correspondance stricte.
