package bj.assistantfon

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Ecran d'activation (PRD F1).
 * Direction : Android-native premium, palette neutre chaude + accent vert,
 * un seul point focal (le bouton d'activation), le reste en liste plate.
 */
class MainActivity : Activity() {

    private lateinit var backend: BackendClient

    private lateinit var iconeService: ImageView
    private lateinit var valeurService: TextView
    private lateinit var iconeMicro: ImageView
    private lateinit var valeurMicro: TextView

    private fun dp(valeur: Int) = (valeur * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = BackendClient(this)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.fond))
        }
        racine.addView(entete())

        val contenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        racine.addView(contenu)

        contenu.addView(panneauHero())

        contenu.addView(sectionTitre(getString(R.string.section_etat)))
        val ligneService = ligneEtat(R.drawable.ic_access, getString(R.string.libelle_service))
        iconeService = ligneService.first
        valeurService = ligneService.second
        contenu.addView(ligneService.third)
        contenu.addView(separateur())
        val ligneMicro = ligneEtat(R.drawable.ic_micro, getString(R.string.libelle_micro))
        iconeMicro = ligneMicro.first
        valeurMicro = ligneMicro.second
        contenu.addView(ligneMicro.third)
        contenu.addView(bouton(getString(R.string.autoriser_micro), primaire = false) { demanderMicro() })
        contenu.addView(bouton(getString(R.string.desactiver_assistant), primaire = false) {
            desactiverAssistant()
        })

        contenu.addView(sectionTitre(getString(R.string.section_serveur)))
        contenu.addView(texte(getString(R.string.url_backend), 14f, getColor(R.color.texte_secondaire))
            .apply { setPadding(0, dp(6), 0, dp(8)) })

        val champUrl = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(getColor(R.color.texte))
            background = getDrawable(R.drawable.champ)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(backend.baseUrl)
        }
        contenu.addView(champUrl)

        contenu.addView(texte(getString(R.string.jeton_backend), 14f, getColor(R.color.texte_secondaire))
            .apply { setPadding(0, dp(16), 0, dp(8)) })

        val champJeton = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(getColor(R.color.texte))
            background = getDrawable(R.drawable.champ)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(backend.jeton)
        }
        contenu.addView(champJeton)
        contenu.addView(texte(getString(R.string.jeton_indice), 13f, getColor(R.color.texte_secondaire))
            .apply { setPadding(0, dp(4), 0, 0) })

        contenu.addView(bouton(getString(R.string.enregistrer_url), primaire = false) {
            val valeur = champUrl.text.toString().trim()
            if (valeur.isNotEmpty()) {
                backend.baseUrl = valeur
                champUrl.setText(backend.baseUrl)
            }
            backend.jeton = champJeton.text.toString()
            Toast.makeText(this, R.string.url_enregistree, Toast.LENGTH_SHORT).show()
        })

        contenu.addView(Switch(this).apply {
            text = getString(R.string.mode_demo)
            textSize = 16f
            setTextColor(getColor(R.color.texte))
            isChecked = backend.modeDemoHorsLigne
            setPadding(0, dp(18), 0, 0)
            setOnCheckedChangeListener { _, coche -> backend.modeDemoHorsLigne = coche }
        })
        contenu.addView(texte(getString(R.string.mode_demo_detail), 13f, getColor(R.color.texte_secondaire))
            .apply { setPadding(0, dp(4), 0, 0) })

        contenu.addView(sectionTitre(getString(R.string.section_aide)))
        contenu.addView(texte(getString(R.string.aide), 15f, getColor(R.color.texte_secondaire)))

        setContentView(ScrollView(this).apply { addView(racine) })

        if (!aPermissionMicro()) demanderMicro()
    }

    override fun onResume() {
        super.onResume()
        majStatuts()
    }

    // ------------------------------------------------------------------- blocs

    private fun entete(): LinearLayout {
        val bandeau = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(12))
        }
        bandeau.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        val textes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        textes.addView(texte(getString(R.string.app_name), 20f, getColor(R.color.texte), gras = true))
        textes.addView(texte(getString(R.string.tagline), 14f, getColor(R.color.texte_secondaire)))
        bandeau.addView(textes)
        return bandeau
    }

    private fun panneauHero(): LinearLayout {
        val panneau = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panneau)
            setPadding(dp(22), dp(24), dp(22), dp(22))
        }
        panneau.addView(texte(getString(R.string.hero_titre), 22f, getColor(R.color.texte), gras = true))
        panneau.addView(texte(getString(R.string.hero_desc), 15f, getColor(R.color.texte_secondaire))
            .apply { setPadding(0, dp(8), 0, 0) })
        panneau.addView(bouton(getString(R.string.bouton_activer), primaire = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.etape_ou_activer, Toast.LENGTH_LONG).show()
        })
        return panneau
    }

    private fun sectionTitre(titre: String): TextView =
        texte(titre.uppercase(), 12f, getColor(R.color.texte_secondaire), gras = true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(28), 0, dp(6))
        }

    /** Renvoie (icone, valeur, ligne complete). */
    private fun ligneEtat(icone: Int, libelle: String): Triple<ImageView, TextView, LinearLayout> {
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val vueIcone = ImageView(this).apply {
            setImageResource(icone)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val texteLibelle = texte(libelle, 16f, getColor(R.color.texte)).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = dp(14) }
        }
        val vueValeur = texte("", 15f, getColor(R.color.texte_secondaire), gras = true)
        ligne.addView(vueIcone)
        ligne.addView(texteLibelle)
        ligne.addView(vueValeur)
        return Triple(vueIcone, vueValeur, ligne)
    }

    private fun separateur(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.separateur))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun texte(contenu: String, taille: Float, couleur: Int, gras: Boolean = false): TextView =
        TextView(this).apply {
            text = contenu
            textSize = taille
            setTextColor(couleur)
            if (gras) setTypeface(typeface, Typeface.BOLD)
        }

    private fun bouton(libelle: String, primaire: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = libelle
            isAllCaps = false
            textSize = 17f
            setTextColor(getColor(if (primaire) R.color.blanc else R.color.primaire))
            background = getDrawable(if (primaire) R.drawable.bouton_primaire else R.drawable.bouton_secondaire)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { topMargin = dp(16) }
        }

    // ------------------------------------------------------------------ etats

    private fun majStatuts() {
        majLigne(iconeService, valeurService, serviceAccessActive(),
            getString(R.string.etat_actif), getString(R.string.etat_inactif))
        majLigne(iconeMicro, valeurMicro, aPermissionMicro(),
            getString(R.string.micro_ok), getString(R.string.micro_ko))
    }

    private fun majLigne(icone: ImageView, valeur: TextView, ok: Boolean, texteOk: String, texteKo: String) {
        val couleur = getColor(if (ok) R.color.ok else R.color.ko)
        icone.setColorFilter(couleur)
        if (ok) icone.setImageResource(R.drawable.ic_check) else icone.setImageResource(R.drawable.ic_alerte)
        valeur.setTextColor(couleur)
        valeur.text = if (ok) texteOk else texteKo
    }

    private fun serviceAccessActive(): Boolean {
        val actifs = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val formes = listOf(
            "$packageName/${GuideService::class.java.name}",
            "$packageName/.GuideService"
        )
        return actifs.split(':').any { actif -> formes.any { it.equals(actif, ignoreCase = true) } }
    }

    /** Coupe le service sans obliger a retrouver le reglage d'accessibilite. */
    private fun desactiverAssistant() {
        val service = GuideService.instance
        if (service == null) {
            Toast.makeText(this, R.string.assistant_absent, Toast.LENGTH_SHORT).show()
            return
        }
        service.desactiver()
        Toast.makeText(this, R.string.assistant_desactive, Toast.LENGTH_SHORT).show()
        majStatuts()
    }

    private fun demanderMicro() {
        if (!aPermissionMicro()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), CODE_MICRO)
        } else {
            majStatuts()
        }
    }

    private fun aPermissionMicro(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CODE_MICRO) majStatuts()
    }

    companion object {
        private const val CODE_MICRO = 1
    }
}
