# PRD — Assistant vocal Fon en overlay Android

**Version :** 2.0 (hackathon) — saisie vocale intégrée
**Cible :** une seule fonctionnalité livrable, démontrable en 3 minutes devant un jury.
**Destinataire du doc :** l'équipe de dev.

---

## 1. Le problème en une phrase

Une femme qui ne lit pas le français ouvre un site administratif (CNSS, état civil) sur son téléphone. Elle ne sait pas quel champ remplir, ni avec quoi, et elle ne peut pas taper la réponse non plus.

## 2. La solution en une phrase

Une appli Android qui affiche une bulle flottante au-dessus de n'importe quelle appli. L'assistant lit l'écran, surligne le champ, explique à l'oral en fon ce qu'il faut dire, **écoute la réponse parlée en fon, l'écrit dans le champ, et relit à voix haute ce qu'il a écrit pour confirmation.**

La boucle complète est : **je t'explique → tu me parles → j'écris → je te relis → tu valides.**

## 3. Ce qui est DANS la V1

| # | Comportement | Critère de réussite |
|---|---|---|
| F1 | Installer l'APK et activer le service en 2 taps | L'utilisateur atterrit sur les Réglages d'accessibilité depuis l'appli |
| F2 | Bulle flottante visible par-dessus toute appli | La bulle survit au changement d'appli et au scroll |
| F3 | Lire les champs de formulaire à l'écran | Les champs d'une page web dans Chrome sont détectés avec leur libellé |
| F4 | Surligner le champ courant | Rectangle coloré dessiné exactement sur le champ |
| F5 | Expliquer le champ à l'oral en fon | Audio déclenché en moins de 2 s après le tap |
| **F6** | **Enregistrer la réponse vocale** | **Appui long sur la bulle = enregistre, relâche = envoie** |
| **F7** | **Écrire la réponse dans le champ** | **Le texte apparaît dans le champ de la page web** |
| **F8** | **Relire à voix haute ce qui a été écrit** | **L'utilisateur entend sa réponse en fon et peut refuser** |

F6 → F8 sont le cœur du projet. Si le temps manque, c'est F4 qu'on sacrifie, pas eux.

## 4. Ce qui est HORS V1

- Login, compte, inscription
- Yoruba (structurer le code pour l'ajouter, ne pas l'implémenter)
- Historique, favoris, réglages
- Hors-ligne
- Soumission automatique du formulaire (elle appuie elle-même sur « Valider », guidée à l'oral)

## 5. Parcours utilisateur cible (le scénario de démo)

1. Ouverture de l'appli → un écran, un gros bouton **« Activer l'assistant »**
2. Le bouton ouvre les Réglages d'accessibilité → l'utilisateur active le service
3. La bulle apparaît en bord d'écran
4. L'utilisateur ouvre Chrome sur le formulaire CNSS et appuie sur la bulle
5. Le champ « Nom » se surligne. Une voix fon dit : *« Ici, dis-moi ton nom de famille. Appuie et parle. »*
6. Elle appuie longuement sur la bulle et dit son nom en fon
7. La bulle passe en état « écoute » (rouge), puis « réflexion » (pulsation)
8. Le nom s'écrit dans le champ. La voix fon relit : *« J'ai écrit : Agbodjan. C'est bon ? »*
9. Elle dit *« oui »* → passage automatique au champ suivant. Elle dit *« non »* → on recommence le champ
10. Arrivé au bouton « Valider », la voix dit : *« Appuie sur ce bouton. »* Le bouton se surligne.

**La démo s'arrête là.**

## 6. Architecture technique

```
┌──────────────────────────────────────────┐
│  App Android (Kotlin)                    │
│                                          │
│  MainActivity   → écran d'activation     │
│  GuideService   → AccessibilityService   │
│    ├─ lecture de l'arbre de nœuds        │
│    ├─ overlay bulle + surlignage         │
│    ├─ MediaRecorder (capture voix)       │
│    ├─ ACTION_SET_TEXT (écriture champ)   │
│    └─ MediaPlayer (lecture audio)        │
└───────────────┬──────────────────────────┘
                │ HTTPS
                ▼
┌──────────────────────────────────────────┐
│  Backend FastAPI — HF Spaces             │
│  POST /guide      → quoi dire            │
│  POST /transcrire → voix fon → valeur    │
│     ├─ ASR : facebook/mms-1b-all (fon)   │
│     ├─ Normaliseur par type de champ     │
│     └─ TTS confirmation : mms-tts-fon    │
└──────────────────────────────────────────┘
```

### 6.1 Le travail de l'agent : du fon parlé vers un champ français

**C'est l'agent qui remplit, pas l'utilisatrice.** Elle parle, il écrit. Entre les deux, il fait trois choses : il transcrit, il met en forme selon le champ, il écrit dans le formulaire.

La mise en forme est plus légère qu'il n'y paraît, parce que **la majorité des champs ne demandent aucune traduction** : un nom reste un nom, un lieu reste un lieu, un numéro reste un numéro. La traduction fon → français ne concerne en réalité qu'une vingtaine de mots, sur les champs à choix.

| Type de champ | Ce qu'elle dit | Ce que fait l'agent | Méthode |
|---|---|---|---|
| Nom, prénom, lieu | Un nom propre | Écrit tel quel | Transcription nettoyée — **rien à traduire** |
| Adresse, profession | Texte libre | Met en forme | **LLM** |
| Sexe, situation matrimoniale | Un mot parmi 2-4 | Choisit l'option française | **Correspondance stricte** |
| Téléphone, CNSS, pièce d'identité | Une suite de chiffres | Assemble les chiffres | **Correspondance stricte**, jamais de LLM |
| Date de naissance | Jour, mois, année | Assemble en 3 temps | Idem, découpé en 3 sous-questions |

**Le piège des nombres.** Le fon utilise un système vigésimal (base 20). « 45 » ne se construit pas du tout comme en français, et un ASR généraliste s'y perd. Ne pas essayer de parser des nombres composés.

**Solution : on demande la dictée chiffre par chiffre.** La voix dit l'équivalent fon de *« Dis-moi ton numéro, un chiffre après l'autre. »* Le backend n'a plus que **10 mots à reconnaître** au lieu d'un système numéral complet. La fiabilité passe de médiocre à excellente. C'est un choix de conception assumé, pas une limitation à cacher : c'est aussi comme ça qu'on dicte un numéro au téléphone.

**La règle de routage : LLM ou correspondance stricte.**

Pour les champs à **texte libre** (profession, adresse), un seul appel LLM remplace tout code de normalisation écrit à la main. On lui passe la transcription fon brute + le type de champ, il renvoie la valeur propre. Moins de code, plus souple, s'adapte aux formulations inattendues.

Pour les champs à **vocabulaire fermé** — les chiffres, oui/non, sexe, situation matrimoniale — **jamais de LLM**. On compare la transcription aux options attendues par distance phonétique et on prend la plus proche. Si rien ne dépasse le seuil, on redemande.

La raison est simple : un LLM comble les trous. Sur un numéro CNSS mal entendu, il produira un numéro plausible et faux, sans jamais signaler qu'il a deviné. Sur ces champs on veut un système qui dit « je n'ai pas compris » plutôt qu'un système qui invente. C'est une contrainte de sécurité, pas une préférence technique.

**Prompt de l'agent (texte libre), à garder court :**
> Tu reçois une transcription en fon et le nom d'un champ de formulaire administratif béninois. Renvoie uniquement la valeur à écrire dans le champ, en français, sans phrase d'accompagnement. Les noms propres se recopient sans traduction. Si la transcription est inexploitable, renvoie exactement `INCOMPRIS`.

Le cas `INCOMPRIS` déclenche la phrase fon *« Je n'ai pas bien entendu, répète »* et relance l'écoute.

### 6.2 La confirmation orale est obligatoire

Elle ne peut pas relire ce qui a été écrit. **Si l'assistant écrit sans relire, l'appli est dangereuse** : elle envoie un dossier administratif avec des erreurs invisibles.

Après chaque écriture, le backend renvoie une phrase de relecture en fon qui reprend la valeur écrite. Pour les chiffres, les relire un par un. Deux réponses possibles : « oui » → champ suivant, « non » → on efface et on recommence.

C'est aussi le meilleur moment de la démo devant le jury. Ne pas le couper faute de temps.

### 6.3 Côté Android

**Config du service** — `res/xml/accessibility_service_config.xml` :
- `android:canRetrieveWindowContent="true"`
- `android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"`
- `android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"`

**Lecture de l'écran.** Partir de `rootInActiveWindow`, parcourir récursivement l'arbre `AccessibilityNodeInfo`. Retenir les nœuds `isEditable == true` ou dont `className` contient `EditText`. Pour le libellé, priorité : `hintText` → `contentDescription` → `text` → texte du nœud frère précédent (souvent le label visuel dans une page web).

**Surlignage.** `node.getBoundsInScreen(rect)` donne les coordonnées. Dessiner une `View` semi-transparente dessus.

**Overlay.** Vues ajoutées via `WindowManager` en `TYPE_ACCESSIBILITY_OVERLAY`. Ce type **ne demande pas la permission `SYSTEM_ALERT_WINDOW`** tant qu'on l'ajoute depuis le contexte du service. Zéro friction à l'installation.

**Capture audio.** `MediaRecorder`, sortie `.m4a` ou `.wav` mono 16 kHz (format attendu par MMS). Permission `RECORD_AUDIO` demandée une seule fois, à l'écran d'activation. Appui long sur la bulle = enregistre, relâchement = envoie.

**Écriture dans le champ.** `node.performAction(ACTION_SET_TEXT, bundle)` avec la valeur dans `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`. Si le champ refuse (certaines WebViews), repli : `ACTION_FOCUS` puis `ACTION_PASTE` depuis le presse-papier. **Tester les deux tôt** — c'est le point le plus susceptible de casser dans Chrome.

**États visuels de la bulle.** Repos (bleu) / écoute (rouge pulsant) / traitement (rotation) / parle (vert). Elle ne lit pas, donc l'état doit être lisible sans un seul mot.

**Pièges.** Chrome ne construit son arbre d'accessibilité complet que si un service le demande : première lecture ~1 s. Les nœuds de WebView n'ont pas toujours de `viewIdResourceName`, ne pas baser la logique dessus. Toujours `recycle()` les nœuds, sinon fuite mémoire et service tué.

### 6.4 Côté backend

```
POST /guide
{ "champs": [ {"index":0,"label":"Nom","type":"text"} ], "index_courant": 0 }
→ { "categorie": "nom", "audio_url": "...", "mode_saisie": "libre" }

POST /transcrire   (multipart : audio + contexte)
{ "audio": <fichier>, "categorie": "telephone" }
→ { "valeur": "97453210",
    "statut": "ok" | "incompris",
    "audio_confirmation_url": "..." }
```

**Pipeline interne de `/transcrire` :**

```
audio .wav
   ↓
MMS-1b-all (fon)  →  texte fon brut
   ↓
        ┌── catégorie à vocabulaire fermé ?
        │      OUI → correspondance phonétique stricte
        │            (échec → statut "incompris")
        │      NON → appel LLM avec le prompt du §6.1
        │            (INCOMPRIS → statut "incompris")
        ↓
valeur française  →  TTS de confirmation  →  réponse
```

**Génération du fon (les consignes).** La traduction automatique français → fon est trop faible pour la fonder dessus. On utilise un **dictionnaire de phrases fon pré-écrites**, une par catégorie de champ, validées par un locuteur et synthétisées **une fois** avec `facebook/mms-tts-fon` en `.wav` statiques. Le backend renvoie une URL. Latence quasi nulle, qualité vérifiée à l'avance, zéro risque de plantage en démo.

**Les confirmations** sont composées à la volée (phrase fixe + valeur). Pour les chiffres, concaténer les `.wav` des 10 chiffres déjà générés — c'est instantané et plus propre que de re-synthétiser.

**Astuce démo.** Si MMS-TTS sur le fon sonne mal à l'écoute, faire enregistrer les ~25 phrases par un locuteur natif. 30 minutes de travail, effet radical devant un jury. Le pipeline MMS reste présenté comme la voie de passage à l'échelle.

## 7. Découpage du travail

| Lot | Contenu | Livrable testable |
|---|---|---|
| **A — Android / service** | Manifest, config XML, écran d'activation, bulle + états visuels | La bulle s'affiche par-dessus Chrome et change d'état |
| **B — Android / lecture & écriture** | Parcours de l'arbre, extraction des champs, surlignage, `ACTION_SET_TEXT` | Un texte en dur s'écrit dans le champ CNSS depuis la bulle |
| **C — Android / audio** | `MediaRecorder`, upload multipart, `MediaPlayer` | Un appui long envoie un `.wav` au backend et joue la réponse |
| **D — Backend consignes** | FastAPI, `/guide`, dictionnaire de catégories, déploiement HF Spaces | L'URL répond en JSON depuis Postman |
| **E — Backend transcription** | `/transcrire`, MMS-1b-all, routage LLM / correspondance stricte, statut `incompris` | Un `.wav` fon de chiffres ressort en `"97453210"` |
| **F — Corpus audio fon** | ~25 phrases + 10 chiffres, génération TTS, validation à l'oreille | Fichiers `.wav` écoutables |

**Contrat d'interface à figer avant tout** : les deux JSON du §6.4. Une fois figé, Android développe contre un backend bouchonné et le backend contre des fichiers d'exemple.

Priorité si l'équipe est réduite : **E et F d'abord**, ce sont les lots les plus incertains.

## 8. Ordre de construction recommandé

1. Bulle flottante qui s'affiche
2. Log des champs détectés dans Chrome
3. **Écriture d'un texte en dur dans un champ** ← vérifier tôt, c'est le point de rupture
4. Enregistrement audio + envoi au backend, réponse jouée
5. Backend `/transcrire` sur les chiffres uniquement
6. Boucle de confirmation oui/non
7. Surlignage visuel
8. Extension aux champs texte libre

À chaque étape l'appli tourne. Si le temps manque, on s'arrête et ça reste démontrable.

## 9. Risques

| Risque | Impact | Parade |
|---|---|---|
| **Qualité ASR sur le fon** | **Bloquant** | Dictée chiffre par chiffre + correspondance stricte + confirmation orale systématique |
| **Le LLM invente une valeur plausible** | **Élevé** | Jamais de LLM sur chiffres et listes fermées (§6.1) ; sortie `INCOMPRIS` obligatoire dans le prompt |
| **Latence MMS-1b-all sur HF Spaces CPU** | **Élevé** | Passer en ZeroGPU ; sinon jouer un audio d'attente fon (*« attends un peu »*) pendant le traitement |
| `ACTION_SET_TEXT` refusé par la WebView | Élevé | Repli `ACTION_PASTE` ; vérifier dès l'étape 3 |
| Labels illisibles dans la WebView | Bloquant | Choisir le site de démo à l'avance et inspecter son arbre dès le lot B |
| Qualité TTS fon décevante | Moyen | Enregistrements humains en secours |
| Pas de réseau en démo | Élevé | `.wav` embarqués dans l'APK + une transcription de secours simulée |

## 10. Le point éthique à préparer pour le jury

L'AccessibilityService voit tout l'écran, et l'appli écoute le micro. C'est exactement le profil d'un spyware. Le jury peut le relever ; prendre les devants.

Trois arguments à tenir, **et à rendre vrais dans le code** :

1. **Le micro n'écoute que sur appui long.** Jamais en continu, jamais de mot-clé de réveil. L'état rouge de la bulle le rend visible.
2. **Rien n'est conservé.** L'audio est traité puis supprimé. Seuls les libellés de champs transitent, jamais les valeurs saisies en dehors du cycle de réponse immédiat. Aucune base de données.
3. **Aucune saisie clavier n'est lue.** On ne s'abonne pas aux événements de frappe, uniquement à la structure des champs.

---

## Annexe — Corpus audio à produire

### Consignes par champ (à valider avec un locuteur)
1. Nom de famille — 2. Prénom — 3. Date de naissance (jour) — 4. (mois) — 5. (année) — 6. Lieu de naissance — 7. Téléphone — 8. Adresse / quartier — 9. Profession — 10. Numéro CNSS — 11. Pièce d'identité — 12. Sexe — 13. Situation matrimoniale — 14. Email

### Phrases de système
15. « Appuie et parle »
16. « Je n'ai pas bien entendu, répète »
17. « J'ai écrit : … c'est bon ? »
18. « D'accord, on recommence »
19. « Dis-moi ton numéro, un chiffre après l'autre »
20. « Attends un peu » (audio d'attente)
21. « Appuie sur ce bouton pour valider »
22. « Tu as fini, c'est bon »
23. Repli : « Réponds pour ce champ »

### Vocabulaire fermé (à reconnaître ET à prononcer)
24. Les chiffres 0 à 9
25. Oui / Non
26. Homme / Femme
