# Assistant Fon

Une bulle flottante qui explique un formulaire en fon, écoute la réponse parlée
et l'écrit à la place de la personne.

---

## Le problème, tel qu'il se pose vraiment

Une commerçante de Dantokpa doit renseigner son numéro CNSS sur un site. Elle a
un téléphone Android, une connexion, et le site est ouvert devant elle. Elle
parle fon toute la journée. Elle ne lit pas le français, et un clavier
alphabétique ne lui sert à rien.

Ce qui se passe ensuite est toujours la même chose : elle attend son fils, ou
elle paie quelqu'un au cybercafé. Elle tend sa pièce d'identité à un inconnu qui
tape à sa place. Souvent, elle renonce.

Ce n'est pas un problème de connexion ni d'équipement. C'est un problème de
langue et d'écriture, et presque personne ne le traite, parce que
l'accessibilité numérique a été pensée pour le handicap visuel. Un lecteur
d'écran lit le français à voix haute : cela n'aide en rien quelqu'un qui ne
comprend pas le français.

## Ce que fait l'application

Une bulle flotte au-dessus de n'importe quelle application. L'utilisatrice la
touche, et l'assistant :

1. regarde l'écran et repère le champ à remplir ;
2. l'entoure et dit en fon ce qu'il faut donner ;
3. écoute la réponse pendant qu'elle garde le doigt appuyé ;
4. écrit la valeur en français dans le champ ;
5. relit ce qu'il a écrit et attend « oui » ou « non ».

**J'explique → tu parles → j'écris → je relis → tu valides.**

Elle garde la main : c'est elle qui appuie sur « Valider » à la fin, guidée à
l'oral. L'assistant ne soumet jamais un formulaire tout seul.

Un bouton vert et un bouton rouge doublent le « oui / non » parlé, parce que
reconnaître un seul mot dans une rue bruyante reste l'étape la plus fragile.

## Pourquoi maintenant

Le Bénin met ses démarches en ligne à un rythme soutenu : état civil et pièces
d'identité, affiliation et cotisations sociales, impôts, visa électronique,
bourses, inscriptions et recensements. Chaque procédure numérisée fait gagner du
temps à ceux qui lisent le français, et construit un mur de plus pour les
autres.

L'interface, elle, ne change pas : du texte, en français, à taper. La
digitalisation avance donc à deux vitesses, et l'écart se creuse au moment
précis où il devrait se réduire.

Assistant Fon s'attaque à cette marche-là, et à rien d'autre.

## Où il s'intègre

Le point important, pour l'adoption : **aucun site n'a besoin d'être modifié.**
L'application lit l'écran par le service d'accessibilité d'Android. Elle ne
demande ni API, ni partenariat, ni intégration côté serveur. Elle fonctionne
par-dessus l'existant, y compris sur un portail qui ne saura jamais qu'elle
existe.

- **Portails publics.** Formulaires d'état civil, sécurité sociale, fiscalité,
  demandes de documents. Zéro coût d'intégration pour l'administration.
- **Guichets et mairies.** Un agent traite une file d'attente ; ici, chaque
  personne remplit elle-même, en fon, et l'agent vérifie au lieu de saisir.
- **Cybercafés et points numériques.** Aujourd'hui, le gérant tape à la place du
  client et voit passer ses papiers. L'assistant lui rend cette saisie.
- **ONG, mutuelles, microfinance.** Recensements de bénéficiaires, dossiers
  d'adhésion, enquêtes de terrain, remplis sur place sans médiateur.
- **Opérateurs et fintech.** Ouverture de compte mobile money, vérification
  d'identité, formulaires de conformité.

## Au-delà du fon

Rien dans l'architecture n'est propre au fon. Une langue tient dans trois
pièces : un modèle de reconnaissance vocale, une voix de synthèse, et un corpus
de phrases traduites. Le yoruba, le bariba, le dendi ou le mina se branchent au
même endroit. Le code sépare déjà ces trois pièces du reste ; seul le fon est
implémenté aujourd'hui.

## Comment c'est fait

| Brique | Technologie |
|---|---|
| Application | Android natif, **Kotlin**, `AccessibilityService` |
| Overlay et surlignage | `TYPE_ACCESSIBILITY_OVERLAY` (aucune permission spéciale) |
| Lecture de l'écran | `takeScreenshot()`, arbre d'accessibilité en repli |
| Capture voix | `AudioRecord`, WAV mono 16 kHz, sur appui long |
| ASR fon | **GRIOT-ASR** (`bivariant/griot-asr`, adapter fon) |
| Traduction fon → français | **Griot-MT** (`bivariant/griot-mt`, adapter fon) |
| Vision d'écran et noms propres | **DeepSeek** |
| Voix fon | `facebook/mms-tts-fon`, `.wav` pré-générés au démarrage |
| Backend | **FastAPI**, kernel **Kaggle** (GPU T4) + tunnel Cloudflare |

Deux règles de sûreté structurent le traitement des réponses :

- **Un nom propre ne se traduit pas.** « Octave » passé à un traducteur ressort
  en paraphrase. Les noms, prénoms et lieux vont donc à un modèle chargé de les
  réécrire correctement, jamais de les traduire.
- **Aucun modèle génératif sur les valeurs fermées.** Oui/non, sexe, situation
  matrimoniale, chiffres : correspondance stricte avec un vocabulaire fon
  validé. Un modèle qui devine un numéro CNSS plausible mais faux fait plus de
  dégâts qu'un modèle qui redemande.

## État actuel

C'est un prototype qui tourne, pas un produit fini.

**Ce qui fonctionne** : la boucle complète sur un formulaire réel, la détection
des champs et des cases à cocher, le surlignage qui suit le défilement, la
dictée chiffre par chiffre, la validation orale ou au bouton.

**Ce qui reste** : faire valider le corpus fon par un locuteur natif, améliorer
la reconstitution des noms béninois, et trouver un hébergement stable — le
kernel Kaggle donne un GPU gratuit, mais l'adresse change à chaque relance.

## Éthique

Le service d'accessibilité voit l'écran et l'application écoute le micro. C'est
exactement le profil d'un logiciel espion, et il n'y a pas de raison de le dire
autrement. Ce que le projet garantit en échange :

1. **Le micro n'écoute que pendant l'appui long.** Jamais en continu, pas de
   mot-clé de réveil.
2. **Rien n'est conservé.** L'audio est effacé du téléphone dès qu'il est
   envoyé, et le backend n'a aucune base de données.
3. **Aucune frappe clavier n'est lue**, uniquement la structure des champs.
4. **La capture d'écran part chez un tiers.** Un appui sur la bulle envoie
   l'écran entier au modèle de vision : tout ce qui est affiché à cet instant
   est transmis. C'est l'utilisatrice qui déclenche chaque envoi, jamais
   l'application.
5. **Le backend doit être protégé.** Définir `API_TOKEN` côté serveur, le même
   jeton dans l'application, et servir en HTTPS. Sans cela, voix et captures
   d'écran circulent sur un point d'entrée ouvert à tous.

## Démarrage rapide

**Application**

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Autoriser le micro, activer le service d'accessibilité, vérifier l'adresse du
backend dans l'écran « Serveur ».

**Backend**

```bash
# app.py et phrases_fon.json sont lus depuis le dépôt Hugging Face : les y pousser d'abord.
kaggle kernels push -p backend/kaggle --accelerator NvidiaTeslaT4
```

L'URL du tunnel s'affiche dans le log du kernel et change à chaque relance. Sur
une machine Ubuntu ou EC2, `bash install.sh` reste valable (voir
`backend/README.md`).

Endpoints : `POST /comprendre`, `POST /transcrire`, `POST /guide`, `GET /sante`.
