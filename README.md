# Assistant Fon

> Une bulle flottante qui aide une personne qui ne lit pas le français à remplir
> un formulaire administratif en **fon**, à la voix.

---

## Le problème

Une femme qui ne lit pas le français ouvre un site administratif (CNSS, état
civil) sur son téléphone. Elle ne sait pas **quel champ remplir**, ni **avec
quoi**, et elle ne peut pas non plus **taper** la réponse. Résultat : elle
dépend de quelqu'un, ou elle abandonne.

## La solution

Une application Android affiche une **bulle flottante** au-dessus de n'importe
quelle application. L'assistant **lit l'écran**, **surligne le champ**, explique
à l'oral **en fon** ce qu'il faut dire, **écoute la réponse parlée**, **l'écrit
dans le champ**, puis **relit à voix haute** ce qu'il a écrit pour confirmation.

La boucle complète est :

**je t'explique → tu me parles → j'écris → je te relis → tu valides.**

## La stack

| Brique | Technologie |
|---|---|
| Application | Android natif, **Kotlin**, `AccessibilityService` |
| Overlay / surlignage | `TYPE_ACCESSIBILITY_OVERLAY` (aucune permission spéciale) |
| Capture voix | `AudioRecord` → **WAV mono 16 kHz** |
| ASR fon | **GRIOT-ASR** (`bivariant/griot-asr`, adapter fon, GPU) |
| ASR français | **Whisper** via l'API **Groq** (noms, accents) |
| TTS fon | `facebook/mms-tts-fon` (VITS, CPU), `.wav` pré-générés |
| Traduction fon→fr | **Griot-MT** (`bivariant/griot-mt`, adapter fon) |
| LLM | **DeepSeek** — noms propres et vision d'écran |
| Backend | **FastAPI** (Python), conteneur Docker |
| Hébergement | Kernel **Kaggle** (GPU T4) + tunnel Cloudflare |
| Code backend | dépôt **Hugging Face Dataset** (public) |

### Règle de routage

- **Noms, prénom, lieu, adresse, profession, email** → Whisper (valeur en français).
- **Oui/non, sexe, situation, chiffres** → MMS (fon), avec **correspondance
  stricte** ; jamais de LLM sur ces champs pour ne pas inventer une valeur
  plausible mais fausse (sécurité).

## Périmètre visé vu le temps imparti

**Dans le périmètre (V1 démontrable en 3 minutes)**

- Installer l'APK et activer le service en 2 taps
- Bulle flottante qui survit au changement d'appli
- Détection des champs et surlignage
- Consigne audio en fon
- Enregistrement à l'appui long, écriture dans le champ
- Relecture et validation orale (oui / non)

**Hors périmètre (assumé)**

- Compte / inscription / historique / réglages
- Yoruba (code structuré pour l'ajouter, non implémenté)
- Saisie hors-ligne complète
- Soumission automatique du formulaire (l'utilisatrice appuie elle-même sur « Valider », guidée à l'oral)

**État actuel**

- Application Android : boucle complète **codée et compilée**.
- Backend : pipeline ASR + TTS + LLM **opérationnel** et déployé.
- Corpus de phrases fon : traduit en fon et intégré au backend.

## Éthique

L'`AccessibilityService` voit l'écran et l'application écoute le micro : c'est le
profil d'un *spyware*. Le projet s'y engage explicitement :

1. **Le micro n'écoute que sur appui long.** Jamais en continu, pas de mot-clé.
2. **Rien n'est conservé.** L'audio est supprimé du téléphone dès qu'il est
   envoyé, et le backend n'a aucune base de données.
3. **Aucune frappe clavier n'est lue**, uniquement la structure des champs.
4. **La capture d'écran part chez un tiers.** Un tap sur la bulle envoie l'écran
   entier à DeepSeek (`/comprendre`) : tout ce qui est affiché à cet instant est
   transmis. L'utilisatrice déclenche elle-même chaque envoi, jamais l'appli.
5. **Le backend doit être protégé.** Définir `API_TOKEN` côté serveur et le même
   jeton dans l'app (écran Serveur), et servir en **HTTPS** : sans cela, voix et
   captures d'écran circulent en clair sur un endpoint ouvert à tous.

## Démarrage rapide

**Application**

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Dans l'app : autoriser le micro, activer le service d'accessibilité, renseigner
l'adresse du backend.

**Backend** (kernel Kaggle, GPU T4)

```bash
# app.py et phrases_fon.json sont lus depuis le depot HF : les y pousser d'abord.
kaggle kernels push -p backend/kaggle --accelerator NvidiaTeslaT4
```

L'URL du tunnel s'affiche dans le log du kernel et **change à chaque relance** :
la recopier dans l'app (écran Serveur). Sur une machine Ubuntu / EC2, l'ancien
chemin reste valable : `bash install.sh` (voir `backend/README.md`).

Endpoints : `POST /guide`, `POST /transcrire`, `GET /sante`.
