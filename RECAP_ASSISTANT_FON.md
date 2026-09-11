# Assistant Fon — Récapitulatif du projet

Résumé de tout ce qui a été fait et décidé (11–12 septembre 2026).
Dernière mise à jour : 12 septembre 2026, après la version **2.1**.

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
  X-Token      : jeton partagé optionnel (API_TOKEN) sur les trois premiers
        │
        ├─ ASR fon ......... GRIOT-ASR   (bivariant/griot-asr, adapter "fon")
        ├─ Traduction ...... Griot-MT    (bivariant/griot-mt, adapter "fon", fon↔fr)
        ├─ Noms propres .... DeepSeek (reconstitution, jamais de traduction)
        ├─ Vocabulaire fermé (oui/non, sexe, chiffres, cases) : correspondance STRICTE
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

### Version 2.1 (12 septembre)
- **Cases à cocher** détectées (case, bouton radio, interrupteur) : l'assistant
  clique (`ACTION_CLICK`) au lieu d'écrire, sur réponse oui/non stricte.
  Nouvelle catégorie `case` côté backend et dans le corpus fon.
- **Bulle déplaçable** au doigt, agrandie à 128 dp, position retenue entre deux
  lancements. Elle ne bouge plus pendant l'enregistrement.
- **Boutons valider / annuler** affichés à côté de la bulle pendant une saisie :
  ils doublent le oui/non parlé, l'étape la plus fragile de la boucle.
- **Désactivation** du service depuis l'écran d'accueil (`disableSelf`).
- **Relecture des noms par le TTS français du téléphone** : le backend renvoie
  `voix_francaise` et les deux phrases fon séparées, l'app intercale la valeur.
- Champ **jeton d'accès** dans l'écran Serveur, et l'URL compilée dans l'APK
  remplace celle enregistrée (sinon une mise à jour garde l'ancien tunnel mort).

### Backend FastAPI
- Endpoints `/guide`, `/comprendre`, `/transcrire`, `/sante`, `/audio` (statique).
- Pipeline ASR + traduction + TTS + vision.
- Garde-fous : rejet des réponses « méta » du LLM (« je ne comprends pas ») et
  des transcriptions non plausibles (nom/lieu/email = court).
- Jeton partagé optionnel `API_TOKEN` (en-tête `X-Token`) sur `/guide`,
  `/comprendre` et `/transcrire`.
- Cache audio : nom de fichier = slug + empreinte, écriture `.wav` atomique,
  génération sérialisée.
- Relecture des choix fermés **en fon** (« Ɛɛn » et non « oui »).
- Déploiement Kaggle (`backend/kaggle/`) : kernel GPU T4, tunnel Cloudflare,
  GRIOT-ASR et Griot-MT greffés sur `app.py` sans le modifier.

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
| Modal | Déployé puis **supprimé du dépôt** | Carte bancaire refusée ; Kaggle fait le même travail |
| Relecture des noms | **TTS français du téléphone**, encadré de phrases fon | Le TTS fon prononce « AGBODJAN » avec les phonèmes fon : inaudible, donc invalidable |
| Oui/non | Doublé par **deux boutons** sur la bulle | Reconnaître un mot unique dans le bruit reste le point faible |
| Cases à cocher | **Clic**, jamais d'écriture, réponse oui/non stricte | `ACTION_SET_TEXT` ne fait rien sur une case |
| Clés sur Kaggle | **Dataset privé** `assistant-fon-cles` déclaré dans `kernel-metadata.json` | Un push par API remet les Add-ons à zéro : les secrets cochés dans l'interface sont perdus à chaque version |

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
6. **Fuite de `MediaPlayer`** : jamais libéré en fin de lecture → `release()`.
7. **Valeur écrite dans le champ de l'écran précédent** quand la vision ne
   retrouvait pas le champ → index remis à -1, repli sur le champ qui a le focus.
8. **L'enregistrement audio n'était jamais effacé** (contraire à l'engagement du
   README) → supprimé dès l'envoi ; micro coupé si le service meurt.
9. **Collision de cache audio** : « Jean-Pierre » et « jean pierre » donnaient le
   même `.wav`, donc la mauvaise relecture → empreinte dans le nom de fichier.
10. **`.wav` servi à moitié écrit** → fichier temporaire puis renommage.
11. **Échec de préparation audio bloquant** : chaque requête attendait 120 s
    → l'événement est posé dans un `finally`.
12. **Parcours d'arbre non borné** dans une WebView → profondeur et nombre de
    champs plafonnés.
13. **Secret Kaggle invisible** après un push par API → clés lues dans un
    dataset privé (voir décisions).

---

## 7. Infra & accès

| Élément | Détail |
|---|---|
| GitHub | `fri25/Waow_Voice` — releases **v1.0** et **v2.0** (APK) |
| Backend HF (code) | dataset `octavebahoun/assistant-fon-backend` |
| Lightsail | `15.188.190.240` (user `admin`), clé `Lightsail-assitant-fon.pem`, port 7860 |
| Kaggle (backend) | kernel privé `octavebahoun/griot-backend`, GPU T4, tunnel Cloudflare, code dans `backend/kaggle/` |
| URL Kaggle (actuelle) | `https://gage-shaved-regression-outlets.trycloudflare.com` (version 4) |
| Clés du kernel | dataset **privé** `octavebahoun/assistant-fon-cles` (`cles.json`) |
| Clés locales | DeepSeek + HF dans `~/IndabaX/.env` (`DEEPSEEK_API_TOKEN`, `HF_TOKEN`) |
| Release | `v2.1` sur GitHub, APK `assistant-fon-v2.1.apk` |

**Kaggle** : le kernel est un *batch* : il continue même si on ferme le
navigateur. Tient jusqu'à **12 h** (le script s'arrête proprement à 11 h).
L'URL du tunnel change à chaque relance : recompiler l'APK, ou la saisir dans
l'écran Serveur.

Limite **2 sessions GPU** simultanées, et une session *éditeur* ouverte avec
« Edit » en occupe une : la fermer avant de pousser une nouvelle version.

Pour récupérer l'URL sans ouvrir le navigateur :
`kaggle kernels logs -f octavebahoun/griot-backend` (flux du run en cours).

---

## 8. Ce qui reste à faire

- Noms béninois : améliorer la reconstruction (actuellement DeepSeek ou
  transcription brute).
- Décider d'un hébergement stable (URL fixe) : ngrok statique gratuit, ou GPU.
  Piste : publier l'URL du tunnel dans le dépôt HF et la faire lire par l'app au
  démarrage, pour ne plus recompiler à chaque relance.
- Valider le corpus fon avec un locuteur natif, dont la phrase `case`
  (« Ðɔ Ɛɛn abǐ gbeɖé nú mì. ») composée à partir de fragments existants.
- Activer `API_TOKEN` : le dépôt GitHub est public et l'APK contient l'adresse
  d'un backend ouvert.
- Tester la détection des cases sur un vrai formulaire de l'État.

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
- `backend/phrases_fon.json` — corpus fon (consignes, système, chiffres, vocabulaire)
- `PRD_assistant_fon.md`, `JOURNAL_DEV.md`, `README.md`, `A_ENREGISTRER.md`
