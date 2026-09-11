package bj.assistantfon

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Service d'accessibilite : bulle flottante, lecture des champs, surlignage,
 * capture vocale, ecriture dans le champ et boucle de confirmation (PRD F1-F8).
 *
 * Boucle : guide -> appui long (enregistre) -> relache (envoie) -> ecriture -> relecture -> oui/non.
 * Le micro n'est actif QUE pendant l'appui long (argument ethique du PRD 10).
 */
class GuideService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var bulle: BulleView
    private lateinit var lecteur: EcranLecteur
    private lateinit var backend: BackendClient
    private val enregistreur by lazy { EnregistreurAudio(this) }

    private lateinit var bulleLp: WindowManager.LayoutParams
    private var boutons: BoutonsView? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsPret = false
    private var surlignage: View? = null

    private val executeur = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val rafraichirRunnable = Runnable { rafraichirSurlignage() }
    private val executorCapture = Executor { commande -> ui.post(commande) }

    private var champs: List<Champ> = emptyList()
    private var indexCourant = 0
    private var phase = Phase.AUCUNE
        set(value) {
            field = value
            if (value == Phase.AUCUNE) masquerBoutons() else afficherBoutons()
        }
    private var enEcoute = false
    private var categorieCourante = TypeChamp.TEXTE
    private var derniereApp: String? = null

    private enum class Phase { AUCUNE, ATTENTE_VALEUR, ATTENTE_CONFIRMATION }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lecteur = EcranLecteur(this)
        backend = BackendClient(this)

        instance = this
        initialiserTts()
        afficherBulle()
        Log.d(TAG, "Service connecte, bulle affichee")
    }

    private fun initialiserTts() {
        tts = TextToSpeech(this) { statut ->
            ttsPret = statut == TextToSpeech.SUCCESS
            if (ttsPret) {
                tts?.language = Locale.FRENCH
                Log.d(TAG, "TTS de secours pret")
            } else {
                Log.w(TAG, "TTS indisponible")
            }
        }
    }

    private fun afficherBulle() {
        if (::bulle.isInitialized) return
        bulle = BulleView(this)
        val metrics = resources.displayMetrics
        val marge = (12 * metrics.density).toInt()
        bulleLp = WindowManager.LayoutParams(
            bulle.taille,
            bulle.taille,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(CLE_X, metrics.widthPixels - bulle.taille - marge)
            y = prefs.getInt(CLE_Y, metrics.heightPixels / 3)
        }
        wm.addView(bulle, bulleLp)
        installerTouches()
    }

    // ------------------------------------------------------------------ touches

    private fun installerTouches() {
        val seuil = ViewConfiguration.get(this).scaledTouchSlop
        var appuiLong = false
        var deplace = false
        var heureAppui = 0L
        var departX = 0
        var departY = 0
        var toucheX = 0f
        var toucheY = 0f
        var rappel: Runnable? = null

        bulle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    appuiLong = false
                    deplace = false
                    heureAppui = System.currentTimeMillis()
                    departX = bulleLp.x
                    departY = bulleLp.y
                    toucheX = event.rawX
                    toucheY = event.rawY
                    rappel = Runnable {
                        if (peutEnregistrer()) {
                            appuiLong = true
                            demarrerEcoute()
                        }
                    }.also { bulle.postDelayed(it, DELAI_APPUI_LONG) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Pendant l'enregistrement le doigt bouge forcement : on ne
                    // deplace pas la bulle, sinon on coupe la parole.
                    if (!enEcoute) {
                        val dx = event.rawX - toucheX
                        val dy = event.rawY - toucheY
                        if (!deplace && hypot(dx, dy) > seuil) {
                            deplace = true
                            rappel?.let { bulle.removeCallbacks(it) }
                        }
                        if (deplace) deplacerBulle(departX + dx.toInt(), departY + dy.toInt())
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    rappel?.let { bulle.removeCallbacks(it) }
                    val duree = System.currentTimeMillis() - heureAppui
                    when {
                        enEcoute -> arreterEtEnvoyer()
                        deplace -> enregistrerPosition()
                        !appuiLong && duree < DELAI_APPUI_LONG -> {
                            bulle.performClick()
                            surTape()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    rappel?.let { bulle.removeCallbacks(it) }
                    when {
                        enEcoute -> arreterEtEnvoyer()
                        deplace -> enregistrerPosition()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun deplacerBulle(x: Int, y: Int) {
        val metrics = resources.displayMetrics
        bulleLp.x = x.coerceIn(0, (metrics.widthPixels - bulle.taille).coerceAtLeast(0))
        bulleLp.y = y.coerceIn(0, (metrics.heightPixels - bulle.taille).coerceAtLeast(0))
        try {
            wm.updateViewLayout(bulle, bulleLp)
        } catch (e: Exception) {
            Log.w(TAG, "deplacement : ${e.message}")
        }
        positionnerBoutons()
    }

    /** La bulle reste ou l'utilisatrice l'a posee, meme apres un redemarrage. */
    private fun enregistrerPosition() {
        prefs.edit().putInt(CLE_X, bulleLp.x).putInt(CLE_Y, bulleLp.y).apply()
    }

    // ------------------------------------------------- boutons valider / annuler

    private fun afficherBoutons() {
        if (boutons != null) {
            positionnerBoutons()
            return
        }
        val vue = BoutonsView(this, surValider = { valider() }, surAnnuler = { annuler() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(vue, lp)
            boutons = vue
            vue.post { positionnerBoutons() }
        } catch (e: Exception) {
            Log.w(TAG, "boutons : ${e.message}")
        }
    }

    /** Les boutons suivent la bulle : a sa gauche, a sa droite s'il manque la place. */
    private fun positionnerBoutons() {
        val vue = boutons ?: return
        val lp = vue.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = resources.displayMetrics
        val largeur = if (vue.width > 0) vue.width else (76 * metrics.density).toInt()
        val hauteur = if (vue.height > 0) vue.height else (152 * metrics.density).toInt()
        val marge = (8 * metrics.density).toInt()

        var x = bulleLp.x - largeur - marge
        if (x < 0) x = bulleLp.x + bulle.taille + marge
        lp.x = x.coerceIn(0, (metrics.widthPixels - largeur).coerceAtLeast(0))
        lp.y = (bulleLp.y + bulle.taille / 2 - hauteur / 2)
            .coerceIn(0, (metrics.heightPixels - hauteur).coerceAtLeast(0))
        try {
            wm.updateViewLayout(vue, lp)
        } catch (e: Exception) {
            Log.w(TAG, "position boutons : ${e.message}")
        }
    }

    private fun masquerBoutons() {
        val vue = boutons ?: return
        boutons = null
        try {
            if (vue.parent != null) wm.removeView(vue)
        } catch (_: Exception) {
        }
    }

    /** Bouton vert : vaut un "oui" parle, ou passe au champ suivant. */
    private fun valider() {
        if (enEcoute) return
        if (phase != Phase.AUCUNE) champSuivant()
    }

    /** Bouton rouge : vaut un "non" parle, sinon arrete la consigne en cours. */
    private fun annuler() {
        if (enEcoute) {
            enregistreur.arreter()?.delete()
            enEcoute = false
        }
        if (phase == Phase.ATTENTE_CONFIRMATION) {
            recommencerChamp()
            return
        }
        arreterTout()
    }

    /** Coupe la parole, efface le surlignage, remet l'assistant au repos. */
    private fun arreterTout() {
        arreterAudio()
        phase = Phase.AUCUNE
        retirerSurlignage()
        changerEtat(Etat.REPOS)
    }

    /** Desactive le service depuis l'ecran d'accueil, sans passer par les reglages. */
    fun desactiver() {
        arreterTout()
        try {
            disableSelf()
        } catch (e: Exception) {
            Log.w(TAG, "disableSelf : ${e.message}")
        }
    }

    private fun peutEnregistrer(): Boolean =
        !enEcoute &&
            (phase == Phase.ATTENTE_VALEUR || phase == Phase.ATTENTE_CONFIRMATION) &&
            aPermissionMicro()

    // ------------------------------------------------------------------- guidage

    /**
     * Tap sur la bulle : on capture l'ecran et on demande au modele vision
     * (usage general). Repli sur la lecture de l'arbre d'accessibilite si la
     * capture est indisponible (API < 30) ou en mode demo.
     */
    private fun surTape() {
        if (enEcoute || phase == Phase.ATTENTE_CONFIRMATION) return
        if (backend.modeDemoHorsLigne || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            guiderParAccessibilite()
            return
        }
        comprendreEcran()
    }

    private fun comprendreEcran() {
        changerEtat(Etat.TRAITEMENT)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executorCapture, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val tampon = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(tampon, result.colorSpace)
                    val octets = if (bitmap != null) {
                        ByteArrayOutputStream().use { flux ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 80, flux)
                            flux.toByteArray()
                        }
                    } else {
                        null
                    }
                    tampon.close()

                    if (octets == null || octets.isEmpty()) {
                        guiderParAccessibilite()
                    } else {
                        envoyerVision(octets)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "capture ecran echouee : $errorCode")
                    guiderParAccessibilite()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot : ${e.message}")
            guiderParAccessibilite()
        }
    }

    private fun envoyerVision(octets: ByteArray) {
        val fichier = File(cacheDir, "ecran_${System.currentTimeMillis()}.png")
        try {
            fichier.writeBytes(octets)
        } catch (e: Exception) {
            Log.w(TAG, "ecriture capture : ${e.message}")
            guiderParAccessibilite()
            return
        }

        executeur.execute {
            val reponse = backend.comprendre(fichier)
            fichier.delete()
            ui.post {
                if (reponse == null || reponse.statut != "ok") {
                    guiderParAccessibilite()
                } else {
                    appliquerVision(reponse)
                }
            }
        }
    }

    private fun appliquerVision(reponse: ReponseVision) {
        categorieCourante = reponse.categorie.ifBlank { TypeChamp.TEXTE }
        phase = Phase.ATTENTE_VALEUR

        val index = localiserChamp(reponse.champ)
        if (index >= 0) {
            indexCourant = index
            focaliser(index)
            surligner(index)
        } else {
            // Aucun champ retrouve : on n'ecrit pas dans celui de l'ecran precedent.
            indexCourant = -1
            retirerSurlignage()
        }

        val audio = backend.urlAbsolue(reponse.audioUrl)
        val secours = reponse.fon ?: SecoursDemo.instruction(categorieCourante)
        jouerAudio(audio, secours) { changerEtat(Etat.REPOS) }
        Log.d(TAG, "Vision champ='${reponse.champ}' cat=$categorieCourante fon='${reponse.fon}'")
    }

    /** Retrouve le noeud correspondant au libelle renvoye par la vision. */
    private fun localiserChamp(label: String?): Int {
        if (label.isNullOrBlank()) return -1
        champs = lecteur.lire()
        if (champs.isEmpty()) return -1
        val cible = normaliserLibelle(label)
        var index = champs.indexOfFirst { normaliserLibelle(it.label) == cible }
        if (index < 0) {
            index = champs.indexOfFirst {
                val l = normaliserLibelle(it.label)
                l.isNotBlank() && (l.contains(cible) || cible.contains(l))
            }
        }
        return index
    }

    private fun normaliserLibelle(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.FRENCH), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun guiderParAccessibilite() {
        champs = lecteur.lire()
        champs.forEachIndexed { i, c ->
            Log.d(TAG, "[$i] label='${c.label}' type=${c.type} bounds=${lecteur.bounds(i)}")
        }
        if (champs.isEmpty()) {
            jouerAudio(null, "Aucun champ detecte a l'ecran.") { changerEtat(Etat.REPOS) }
            return
        }
        if (indexCourant >= champs.size) indexCourant = 0
        allerAuChamp(indexCourant)
    }

    private fun allerAuChamp(index: Int) {
        val champ = champs.getOrNull(index) ?: return
        indexCourant = index
        categorieCourante = champ.type
        phase = Phase.ATTENTE_VALEUR

        focaliser(index)
        surligner(index)
        changerEtat(Etat.PARLE)

        executeur.execute {
            val reponse = backend.guide(champs, index)
            ui.post {
                val categorie = reponse?.categorie?.ifBlank { champ.type } ?: champ.type
                categorieCourante = categorie
                val audio = backend.urlAbsolue(reponse?.audioUrl)
                jouerAudio(audio, SecoursDemo.instruction(categorie)) {
                    changerEtat(Etat.REPOS)
                }
                Log.d(TAG, "Champ $index '${champ.label}' type=$categorie audio=${audio != null}")
            }
        }
    }

    // -------------------------------------------------------------------- audio

    private fun demarrerEcoute() {
        if (enregistreur.demarrer()) {
            enEcoute = true
            changerEtat(Etat.ECOUTE)
            Log.d(TAG, "Ecoute demarree (phase=$phase)")
        } else {
            Log.w(TAG, "Demarrage micro impossible")
            changerEtat(Etat.REPOS)
        }
    }

    private fun arreterEtEnvoyer() {
        val fichier = enregistreur.arreter()
        enEcoute = false
        if (fichier == null) {
            changerEtat(Etat.REPOS)
            return
        }

        changerEtat(Etat.TRAITEMENT)
        val enConfirmation = phase == Phase.ATTENTE_CONFIRMATION
        val categorie = if (enConfirmation) TypeChamp.OUI_NON else categorieCourante

        executeur.execute {
            val reponse = backend.transcrire(fichier, categorie)
            // Rien n'est conserve : l'audio est efface des qu'il est envoye.
            val taille = fichier.length()
            fichier.delete()
            Log.d(TAG, "Audio envoye puis efface ($taille octets)")
            ui.post {
                if (enConfirmation) traiterConfirmation(reponse) else traiterValeur(reponse)
            }
        }
    }

    private fun traiterValeur(reponse: ReponseTranscription?) {
        Log.d(TAG, "VALEUR cat=$categorieCourante statut=${reponse?.statut} " +
            "asr='${reponse?.transcription}' valeur='${reponse?.valeur}'")
        if (reponse == null || reponse.statut != "ok" || reponse.valeur.isNullOrBlank()) {
            if (reponse == null) {
                // Backend joignable non / mode demo : on deroule la boucle.
                val valeur = SecoursDemo.valeur(categorieCourante)
                ecrireValeur(valeur)
                phase = Phase.ATTENTE_CONFIRMATION
                jouerAudio(null, SecoursDemo.confirmation(valeur)) { changerEtat(Etat.REPOS) }
            } else {
                phase = Phase.ATTENTE_VALEUR
                jouerAudio(backend.urlAbsolue(reponse.audioConfirmationUrl), SecoursDemo.reponseIncomprise()) {
                    changerEtat(Etat.REPOS)
                }
            }
            return
        }

        val valeur = reponse.valeur
        ecrireValeur(valeur)
        phase = Phase.ATTENTE_CONFIRMATION
        relire(reponse, valeur)
    }

    /**
     * Relecture avant validation. Le TTS fon prononce un nom francais avec les
     * phonemes fon : inaudible, donc impossible a valider. Pour ces champs on
     * garde les phrases fon du backend et on intercale la valeur dite par le
     * TTS francais du telephone.
     */
    private fun relire(reponse: ReponseTranscription, valeur: String) {
        if (reponse.voixFrancaise) {
            jouerSequence(
                listOf(
                    backend.urlAbsolue(reponse.audioAvantUrl) to SecoursDemo.jaiEcrit(),
                    null to valeur,
                    backend.urlAbsolue(reponse.audioApresUrl) to SecoursDemo.estBon()
                )
            ) { changerEtat(Etat.REPOS) }
            return
        }
        jouerAudio(backend.urlAbsolue(reponse.audioConfirmationUrl), SecoursDemo.confirmation(valeur)) {
            changerEtat(Etat.REPOS)
        }
    }

    private fun traiterConfirmation(reponse: ReponseTranscription?) {
        Log.d(TAG, "CONFIRM statut=${reponse?.statut} " +
            "asr='${reponse?.transcription}' valeur='${reponse?.valeur}'")
        if (reponse == null) {
            // Mode demo hors-ligne : pas de transcription, on avance.
            champSuivant()
            return
        }
        if (reponse.statut != "ok" || reponse.valeur.isNullOrBlank()) {
            phase = Phase.ATTENTE_CONFIRMATION
            jouerAudio(backend.urlAbsolue(reponse.audioConfirmationUrl), SecoursDemo.reponseIncomprise()) {
                changerEtat(Etat.REPOS)
            }
            return
        }
        val valeur = reponse.valeur.lowercase(Locale.FRENCH).trim()
        when {
            valeur.contains("oui") -> champSuivant()
            valeur.contains("non") -> recommencerChamp()
            else -> {
                phase = Phase.ATTENTE_CONFIRMATION
                jouerAudio(backend.urlAbsolue(reponse.audioConfirmationUrl), SecoursDemo.reponseIncomprise()) {
                    changerEtat(Etat.REPOS)
                }
            }
        }
    }

    private fun champSuivant() {
        if (indexCourant >= 0 && indexCourant + 1 < champs.size) {
            allerAuChamp(indexCourant + 1)
        } else {
            phase = Phase.AUCUNE
            retirerSurlignage()
            jouerAudio(null, SecoursDemo.fin()) { changerEtat(Etat.REPOS) }
        }
    }

    private fun recommencerChamp() {
        ecrireValeur("")
        if (champs.getOrNull(indexCourant) == null) {
            phase = Phase.ATTENTE_VALEUR
            jouerAudio(null, SecoursDemo.instruction(categorieCourante)) { changerEtat(Etat.REPOS) }
            return
        }
        allerAuChamp(indexCourant)
    }

    /**
     * Joue l'audio du backend s'il existe, sinon un repli : TTS francais si
     * disponible, sinon une pause. onFin est appele une seule fois.
     */
    private fun jouerAudio(url: String?, texteSecours: String, onFin: () -> Unit) {
        arreterAudio()
        changerEtat(Etat.PARLE)

        if (url != null) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener { finirAudio(onFin) }
                mp.setOnErrorListener { _, _, _ ->
                    finirAudio(onFin)
                    true
                }
                mediaPlayer = mp
                mp.prepareAsync()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Lecture URL impossible : ${e.message}")
            }
        }

        if (ttsPret && tts != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    ui.post { finirAudio(onFin) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    ui.post { finirAudio(onFin) }
                }
            })
            tts?.speak(texteSecours, TextToSpeech.QUEUE_FLUSH, null, "assistantfon")
        } else {
            ui.postDelayed({ finirAudio(onFin) }, 1500)
        }
    }

    private fun jouerSequence(elements: List<Pair<String?, String>>, onFin: () -> Unit) {
        val premier = elements.firstOrNull()
        if (premier == null) {
            onFin()
            return
        }
        jouerAudio(premier.first, premier.second) { jouerSequence(elements.drop(1), onFin) }
    }

    private fun finirAudio(onFin: () -> Unit) {
        val mp = mediaPlayer
        mediaPlayer = null
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        onFin()
    }

    private fun arreterAudio() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ ecriture

    private fun focaliser(index: Int) {
        try {
            lecteur.noeud(index)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (e: Exception) {
            Log.w(TAG, "focus : ${e.message}")
        }
    }

    private fun ecrireValeur(valeur: String): Boolean {
        val node = lecteur.noeud(indexCourant) ?: lecteur.noeudFocalise()
        if (node == null) {
            Log.w(TAG, "Aucun noeud pour le champ $indexCourant")
            return false
        }
        if (node.isCheckable) return cocher(node, valeur)
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valeur)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.d(TAG, "ACTION_SET_TEXT OK : $valeur")
                true
            } else {
                // Repli WebView : presse-papier + collage (PRD 6.3).
                val presse = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                presse.setPrimaryClip(ClipData.newPlainText("assistantfon", valeur))
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val colle = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "Repli ACTION_PASTE : $colle")
                colle
            }
        } catch (e: Exception) {
            Log.w(TAG, "ecriture : ${e.message}")
            false
        }
    }

    /** Case a cocher : on ne clique que si l'etat demande differe de l'etat actuel. */
    private fun cocher(node: AccessibilityNodeInfo, valeur: String): Boolean {
        val reponse = valeur.lowercase(Locale.FRENCH)
        val veutCocher = reponse.contains("oui") || reponse.contains("\u025b\u025bn") || reponse == "1"
        if (node.isChecked == veutCocher) return true
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK).also {
                Log.d(TAG, "case : cochee=$veutCocher ok=$it")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cocher : ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- surlignage

    private fun surligner(index: Int) {
        val rect = lecteur.bounds(index) ?: run {
            retirerSurlignage()
            return
        }
        val marge = (6 * resources.displayMetrics.density).toInt()
        rect.inset(-marge, -marge)
        val hauteurMin = (44 * resources.displayMetrics.density).toInt()
        if (rect.height() < hauteurMin) {
            val centre = rect.centerY()
            rect.top = centre - hauteurMin / 2
            rect.bottom = centre + hauteurMin / 2
        }

        val vue = surlignage ?: View(this).apply {
            background = getDrawable(R.drawable.surlignage_fond)
            isClickable = false
            isFocusable = false
        }.also { surlignage = it }

        val lp = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = rect.left
        lp.y = rect.top

        try {
            if (vue.parent == null) wm.addView(vue, lp) else wm.updateViewLayout(vue, lp)
        } catch (e: Exception) {
            Log.w(TAG, "surlignage : ${e.message}")
        }
    }

    private fun retirerSurlignage() {
        val vue = surlignage ?: return
        try {
            if (vue.parent != null) wm.removeView(vue)
        } catch (_: Exception) {
        }
        surlignage = null
    }

    /**
     * Le scroll deplace le champ sous le rectangle fixe : on le suit. On
     * rafraichit le noeud (coordonnees a jour) et, s'il a ete recycle par la
     * WebView, on relit l'ecran pour retrouver le meme champ. Evenements
     * tempores pour ne pas parcourir l'arbre a chaque frame.
     */
    private fun planifierRafraichissement() {
        if (enEcoute) return
        if (phase != Phase.ATTENTE_VALEUR && phase != Phase.ATTENTE_CONFIRMATION) return
        ui.removeCallbacks(rafraichirRunnable)
        ui.postDelayed(rafraichirRunnable, DELAI_RAFRAICHISSEMENT)
    }

    private fun rafraichirSurlignage() {
        if (enEcoute) return
        if (phase != Phase.ATTENTE_VALEUR && phase != Phase.ATTENTE_CONFIRMATION) return
        if (lecteur.rafraichir(indexCourant)) {
            surligner(indexCourant)
        } else if (relocaliserChamp()) {
            surligner(indexCourant)
        } else {
            retirerSurlignage()
        }
    }

    /** Retrouve le champ courant apres relecture, par libelle puis par position/type. */
    private fun relocaliserChamp(): Boolean {
        val ancien = champs.getOrNull(indexCourant) ?: return false
        val label = ancien.label
        val type = ancien.type
        val position = indexCourant

        val nouveaux = lecteur.lire()
        if (nouveaux.isEmpty()) return false
        champs = nouveaux

        val parLabel = if (label.isNotBlank()) nouveaux.indexOfFirst { it.label == label } else -1
        val parPosition = if (position in nouveaux.indices) position else -1
        val parType = nouveaux.indexOfFirst { it.type == type }
        val index = when {
            parLabel >= 0 -> parLabel
            parPosition >= 0 -> parPosition
            parType >= 0 -> parType
            else -> -1
        }
        if (index < 0) return false
        indexCourant = index
        return true
    }

    private fun changerEtat(etat: Etat) {
        if (::bulle.isInitialized) bulle.changerEtat(etat)
    }

    private fun aPermissionMicro(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ cycle de vie

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val paquet = e.packageName?.toString() ?: return
                if (paquet == derniereApp) return
                derniereApp = paquet

                // On repart propre quand l'utilisateur change d'application.
                if (phase == Phase.AUCUNE && !enEcoute) {
                    lecteur.recycler()
                    champs = emptyList()
                    retirerSurlignage()
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> planifierRafraichissement()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(rafraichirRunnable)
        if (enEcoute) {
            enEcoute = false
            enregistreur.arreter()?.delete()
        }
        try {
            executeur.shutdown()
        } catch (_: Exception) {
        }
        arreterAudio()
        masquerBoutons()
        instance = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        retirerSurlignage()
        if (::lecteur.isInitialized) lecteur.recycler()
        if (::bulle.isInitialized) {
            try {
                wm.removeView(bulle)
            } catch (_: Exception) {
            }
        }
        Log.d(TAG, "Service detruit")
    }

    companion object {
        const val TAG = "GuideService"
        private const val DELAI_APPUI_LONG = 350L
        private const val DELAI_RAFRAICHISSEMENT = 120L
        private const val PREFS = "assistantfon"
        private const val CLE_X = "bulle_x"
        private const val CLE_Y = "bulle_y"

        /** Service en cours, pour que l'ecran d'accueil puisse le desactiver. */
        @Volatile
        var instance: GuideService? = null
            private set
    }
}
