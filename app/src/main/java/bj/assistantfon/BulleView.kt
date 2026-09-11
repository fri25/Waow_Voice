package bj.assistantfon

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView

enum class Etat { REPOS, ECOUTE, TRAITEMENT, PARLE }

/**
 * Bulle flottante avec etats visuels lisibles sans un mot (PRD 6.3) :
 * repos (bleu) / ecoute (rouge pulsant) / traitement (orange rotatif) / parle (vert).
 */
class BulleView(context: Context) : TextView(context) {

    private val fond = GradientDrawable()
    private val animations = mutableListOf<ObjectAnimator>()

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        elevation = 10f

        val taille = (100 * resources.displayMetrics.density).toInt()
        layoutParams = ViewGroup.LayoutParams(taille, taille)

        fond.shape = GradientDrawable.OVAL
        fond.setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
        background = fond

        changerEtat(Etat.REPOS)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun changerEtat(etat: Etat) {
        arreterAnimation()
        scaleX = 1f
        scaleY = 1f
        rotation = 0f

        val couleur: Int
        when (etat) {
            Etat.REPOS -> {
                text = context.getString(R.string.bulle_repos)
                couleur = context.getColor(R.color.bulle_repos)
            }
            Etat.ECOUTE -> {
                text = context.getString(R.string.bulle_ecoute)
                couleur = context.getColor(R.color.bulle_ecoute)
                lancerPulsation()
            }
            Etat.TRAITEMENT -> {
                text = context.getString(R.string.bulle_traitement)
                couleur = context.getColor(R.color.bulle_traitement)
                lancerRotation()
            }
            Etat.PARLE -> {
                text = context.getString(R.string.bulle_parle)
                couleur = context.getColor(R.color.bulle_parle)
            }
        }
        fond.setColor(couleur)
        background = fond
    }

    private fun lancerPulsation() {
        listOf("scaleX", "scaleY").forEach { propriete ->
            animations.add(
                ObjectAnimator.ofFloat(this, propriete, 1f, 1.18f).apply {
                    duration = 450
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            )
        }
    }

    private fun lancerRotation() {
        animations.add(
            ObjectAnimator.ofFloat(this, "rotation", 0f, 360f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        )
    }

    private fun arreterAnimation() {
        animations.forEach { it.cancel() }
        animations.clear()
    }
}
