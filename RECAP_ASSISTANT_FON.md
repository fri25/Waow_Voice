# Assistant Fon — Récapitulatif du projet

Résumé de tout ce qui a été fait et décidé (11–12 septembre 2026).

---

## 1. Le projet en deux lignes

Application Android avec une **bulle flottante** qui aide une personne qui ne
lit pas le français à remplir un formulaire (ou n'importe quel écran) : elle
**lit l'écran**, **explique en fon à l'oral**, **écoute la réponse parlée**,
**écrit la valeur en français** dans le champ, puis **relit pour confirmation**.

Boucle : `j'explique → tu parles → j'écris → je relis → tu valides`.

---

## 2. Architecture retenue

```
Android (Kotlin)
  Accessibilité : lire les champs, surligner, écrire (ACTION_SET_TEXT)
  Capture écran : AccessibilityService.takeScreenshot()  (API 30+, sans permission)
  Audio : AudioRecord WAV mono 16 kHz, appui long sur la bulle
        │  HTTPS
        ▼
Backend FastAPI
  /comprendre  : capture d'écran → DeepSeek vision → consigne en fon + audio
  /transcrire  : audio → GRIOT-ASR (fon) → valeur française
  /guide       : consigne pré-écrite par catégorie (repli)
  /sante       : état du service
        │
        ├─ ASR fon ......... GRIOT-ASR   (bivariant/griot-asr, adapter "fon")
        ├─ Traduction ...... Griot-MT    (bivariant/griot-mt, adapter "fon", fon↔fr)
        ├─ Noms propres .... DeepSeek (reconstitution, jamais de traduction)
        ├─ Vocabulaire fermé (oui/non, sexe, chiffres) : correspondance STRICTE
        ├─ Vision .......... DeepSeek Flash (deepseek-flash), image acceptée
        └─ Voix ............ facebook/mms-tts-fon (consignes fon)
```

---

## 3. Ce qui a été construit

### Application Android
- Écran d'activation (micro, réglages d'accessibilité, adresse backend, mode démo).
- `AccessibilityService` : bulle flottante, lecture de l'arbre, surlignage,
  écriture dans le champ, capture audio, relecture.
- Machine à états : consigne → appui long (enregistre) → relâche (envoie) →
  écriture → relecture → validation orale (oui/non).
- **Capture d'écran** via `takeScreenshot()` (API 30+) : usage général, sans
  permission supplémentaire. Repli sur l'arbre d'accessibilité sinon.
- Suivi du surlignage au **scroll** (rafraîchissement du nœud + relocalisation).
- Détection des **libellés** améliorée (label englobant, nettoyage, repli sur l'id).
- Client HTTP `HttpURLConnection` (multipart) vers `/guide`, `/comprendre`,
  `/transcrire`.
- Mode démo hors-ligne **désactivé par défaut** (utilise le vrai backend).

### Backend FastAPI
- Endpoints `/guide`, `/comprendre`, `/transcrire`, `/sante`, `/audio` (statique).
- Pipeline ASR + traduction + TTS + vision.
- Garde-fous : rejet des réponses « méta » du LLM (« je ne comprends pas ») et
  des transcriptions non plausibles (nom/lieu/email = court).

---

## 4. Décisions clés

| Sujet | Décision | Raison |
|---|---|---|
| Lecture de l'écran | **Vision DeepSeek Flash** (capture) + arbre d'accessibilité en local | Usage **général**, pas seulement les formulaires ; l'arbre reste gratuit |
| ASR fon | **GRIOT-ASR** au lieu de MMS | Bien meilleur (diacritiques, parler naturel), rapide (0,6–1,9 s) |
| Traduction fon→fr | **Griot-MT** | Traduit la réponse en français au lieu d'un bricolage LLM |
| Noms propres | **Jamais traduits** → DeepSeek reconstruit, sinon transcription brute | Un nom béninois n'est pas un mot à traduire |
| Vocabulaire fermé | **Correspondance stricte**, jamais MT/LLM | Ne pas inventer un numéro/oui-non plausible mais faux |
| TTS | **MMS-TTS-fon** pour le fon | Pas de TTS chez Griot |
| Hébergement | **Kaggle gratuit** (GPU T4 + tunnel Cloudflare) | Pas de carte bancaire ; Lightsail CPU (4 Go) trop faible pour GRIOT |
| Modal | Déployé mais **abandonné** | Carte bancaire refusée pour débloquer le crédit |

---

## 5. Tests & résultats

- **TTS MMS fon** : prononciation validée à l'oreille (WAV 16 kHz).
- **DeepSeek vision** sur `form.png` : identifie correctement « Nom de l'équipe ».
- **DeepSeek zéro-shot en fon** : faible (erreur sur les nombres : `tɛnwe`=7 au
  lieu de `ɛnɛ`=4). **Avec few-shot du corpus** : nettement meilleur.
- **GRIOT-ASR** (Kaggle T4) : fonctionne, rapide, transcription correcte.
- **Griot-MT** :
  - `Ŋlɔ nyikɔ towe ɖò fí.` → « Inscris ici ton nom. » (excellent)
  - `Donne-moi ton nom de famille.` → `Đɔ nyǐkɔ towe nú mì.`
  - `Ɛɛn` → « Oui, oui. »
  - **Raté** : `gbeɖé` → « jamais » au lieu de « non » → à garder en
    correspondance stricte.
- **Boucle oui/non** : validée avec une vraie voix humaine (MMS entendait `εε`
  → `oui`). Le test TTS→ASR était trop pessimiste.

---

## 6. Bugs trouvés et corrigés

1. **Le LLM écrivait « Je ne comprends pas. » dans le champ**
   → garde-fou `est_reponse_meta` + plage de plausibilité (`est_valeur_plausible`).
2. **Les noms étaient traduits** (ex. « Octave » → « le fleuve de l'Euphrate »)
   → les noms ne passent plus par Griot-MT, mais par DeepSeek.
3. `torchao` 0.10 incompatible avec `peft` sur Kaggle
   → `pip uninstall torchao`.
4. GPU Kaggle attribué en **P100** (PyTorch récent ne le supporte plus)
   → forcer `--accelerator NvidiaTeslaT4`.
5. Mauvaise version d'`app.py` sur HF (sans les garde-fous)
   → `app.py` repoussé sur le dépôt HF.

---

## 7. Infra & accès

| Élément | Détail |
|---|---|
| GitHub | `fri25/Waow_Voice` — releases **v1.0** et **v2.0** (APK) |
| Backend HF (code) | dataset `octavebahoun/assistant-fon-backend` |
| Lightsail | `15.188.190.240` (user `admin`), clé `Lightsail-assitant-fon.pem`, port 7860 |
| Kaggle (backend démo) | kernel privé `octavebahoun/griot-backend`, GPU T4, tunnel Cloudflare |
| URL Kaggle (actuelle) | `https://collapse-salmon-ratios-monitors.trycloudflare.com` |
| Modal (repli) | `https://octavebahoun--assistant-fon-backend-api.modal.run` |
| Clés | DeepSeek + HF dans `~/IndabaX/.env` (`DEEPSEEK_API_TOKEN`, `HF_TOKEN`) |
| Secret Modal | `assistant-fon` (DEEPSEEK_API_KEY) |

**Kaggle** : le kernel est un *batch* : il continue même si on ferme le
navigateur. Tient jusqu'à **12 h**. L'URL de tunnel change à chaque relance.
Limite **2 sessions GPU** simultanées → pour repousser, il faut supprimer le
kernel d'abord.

---

## 8. Ce qui reste à faire

- Brancher l'APK sur l'URL du backend (champ « Adresse du backend »).
- Noms béninois : améliorer la reconstruction (actuellement DeepSeek ou
  transcription brute).
- Confirmer la prononciation de la valeur avec un **TTS français** pour les
  noms (au lieu du TTS fon).
- Décider d'un hébergement stable (URL fixe) : ngrok statique gratuit, ou GPU.
- Valider le corpus fon avec un locuteur natif.

---

## 9. Fichiers utiles

Dans `~/Téléchargements/` :
- `GRIOT_resultats.txt` — résultats du test GRIOT
- `griot-fon-test.log` — log brut
- `GRIOT_test.py` — script du kernel de test
- `kaggle_griot/` — kernel + métadonnées
- `kaggle_griot.sh` — runner (push + attente + récupération)

Dans le projet :
- `backend/app.py` — backend FastAPI
- `backend/kaggle/` — kernel du backend Kaggle (GPU T4 + tunnel Cloudflare)
- `PRD_assistant_fon.md`, `JOURNAL_DEV.md`, `README.md`
