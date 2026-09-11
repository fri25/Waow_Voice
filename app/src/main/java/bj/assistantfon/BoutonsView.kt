package bj.assistantfon

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Deux boutons ronds a cote de la bulle : valider et annuler.
 *
 * Ils doublent la reponse orale oui/non, qui est l'etape la plus fragile de la
 * boucle (reconnaissance d'un seul mot). Ici, aucun risque d'erreur d'ecoute.
 */
class BoutonsView(
    context: Context,
    surValider: () -> Unit,
    surAnnuler: () -> Unit
) : LinearLayout(context) {

    private val densite = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(bouton("✓", R.color.ok, R.string.bouton_valider, surValider))
        addView(bouton("✕", R.color.ko, R.string.bouton_annuler, surAnnuler))
    }

    private fun bouton(signe: String, couleur: Int, description: Int, action: () -> Unit): TextView {
        val taille = (62 * densite).toInt()
        val marge = (7 * densite).toInt()
        return TextView(context).apply {
            text = signe
            contentDescription = context.getString(description)
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            elevation = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(couleur))
                setStroke((3 * densite).toInt(), Color.WHITE)
            }
            layoutParams = LayoutParams(taille, taille).apply { setMargins(0, marge, 0, marge) }
            setOnClickListener { action() }
        }
    }
}
