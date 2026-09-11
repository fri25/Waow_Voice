# Journal de développement — Waow Voice / Assistant Fon

Ce document récapitule **ce qui a été fait** et les **décisions (résolutions)
prises** au fil du projet.

---

## 1. Ce qui a été fait

### Environnement
- JDK 17 vérifié, **SDK Android** (plateformes 35 et 36, build-tools) installé.
- **Gradle 8.13** + wrapper générés pour le projet.

### Application Android (Kotlin)
- Écran d'activation (permission micro, accès aux réglages d'accessibilité,
  adresse du backend, mode démo hors-ligne).
- `AccessibilityService` : bulle flottante, lecture de l'arbre de nœuds,
  surlignage, écriture dans le champ, capture audio, relecture.
- Machine à états complète : consigne → appui long (enregistre) → relâche
  (envoie) → écriture → relecture → validation orale (oui/non).
- UI refaite : palette neutre chaude + accent vert, icône de marque, bouton
  pilule, bulle agrandie, statuts en liste.
- Client HTTP minimal (`HttpURLConnection`, multipart) vers `/guide` et `/transcrire`.

### Backend (FastAPI, Docker)
- Endpoints `/guide`, `/transcrire`, `/sante`.
- **ASR fon** : `Professor/mms-300m-fongbe`.
- **ASR français** : Whisper via **Groq**.
- **TTS fon** : `facebook/mms-tts-fon`, `.wav` pré-générés au démarrage.
- **LLM** : **DeepSeek** pour les champs à texte libre.
- Normalisation phonétique (Levenshtein) et dictée chiffre par chiffre.
- Conteneur Docker avec modèles pré-téléchargés ; `install.sh` clé en main.

### Déploiement & outils
- Code backend publié dans un **dépôt Hugging Face Dataset** (gratuit).
- Backend déployé sur **AWS Lightsail** (IP statique, port 7860).
- **Git** initialisé, `.gitignore`, code poussé sur GitHub
  (`fri25/Waow_Voice`), **release v1.0** avec l'APK.

---

## 2. Décisions prises (problème → résolution)

| # | Problème rencontré | Décision / résolution | Raison |
|---|---|---|---|
| 1 | HF Spaces (Gradio/Docker) devenus payants (PRO) | Héberger sur **AWS Lightsail** (crédits) | 100 % gratuit avec les crédits, GPU/CPU suffisant |
| 2 | Besoin d'une machine gratuite et puissante | Lightsail `t3.medium` / 4 Go | Modèle 300M tient en RAM, prix fixe |
| 3 | Diffuser le code vers l'EC2 sans compte/transfert | Dépôt **HF Dataset public** + `install.sh` | Clone en une commande, gratuit |
| 4 | `transformers` 4.x plantait sur la config du modèle (`extra_special_tokens`) | Passer à **transformers 5.x** | Le modèle a été sauvegardé avec la 5.0 |
| 5 | Le backend renvoie `"valeur": null`, lu comme le texte `"null"` | Lecture stricte du JSON (`chaineOuNull`) | Sinon boucle infinie « incompris » |
| 6 | L'appli restait bloquée à redemander | Distinguer `statut` (ok / incompris) et re-demander proprement | Ne plus bloquer, ne plus avancer à tort |
| 7 | Noms transcrits phonétiquement (`pxε syo`) | Route vers **DeepSeek** avec prompt de reconstitution FR | Le formulaire attend du français |
| 8 | LLM dangereux sur chiffres/listes | **Jamais de LLM** sur ces champs → correspondance stricte | Ne pas inventer un numéro plausible mais faux |
| 9 | Modèle fon faible sur le français/accents | **Whisper (Groq)** pour les champs FR, **MMS** pour le fon | Chaque modèle sur son terrain |
| 10 | Les gens parlent **fon ET français** | Essayer **les deux ASR** et accepter si l'un correspond | Réalité du terrain |
| 11 | L'ASR sortait l'epsilon **grec** `ε` au lieu du fon `ɛ` | **Normalisation des caractères confondus** + seuil 0.4 | `εε` doit matcher `Ɛɛn` |
| 12 | Libellés de champs vides dans Chrome | Chercher le texte **au-dessus** du champ, sur plusieurs ancêtres | Le label HTML est hors du champ |
| 13 | Surlignage mal placé | Retrait de `FLAG_LAYOUT_NO_LIMITS`, hauteur minimale | Alignement correct |
| 14 | Qualité TTS fon incertaine | Garder le TTS **+ plan B** : enregistrements humains des ~25 phrases | Sécurité en démo |
| 15 | Clés API exposées en clair | **Rotation** des clés, passage par variables d'environnement | Sécurité |
| 16 | Bulle trop petite / bouton peu lisible | Bulle **100 dp**, bouton **pilule 60 dp** | Utilisabilité (utilisatrice peu alphabétisée) |

---

## 3. État actuel

- **App Android** : boucle complète codée et compilée, installable.
- **Backend** : pipeline ASR + TTS + LLM opérationnel et déployé.
- **Corpus fon** : phrases traduites en fon et intégrées au backend (`phrases_fon.json`).

## 4. Reste à faire

- Plan B corpus : enregistrement humain des phrases si le TTS fon est jugé insuffisant.
- Affiner la détection des libellés et le suivi du surlignage au scroll.
- Sécuriser les clés (régénérées) et envisager un APK signé en release.
- Décider du nom final entre *Waow Voice* et *Assistant Fon*.
