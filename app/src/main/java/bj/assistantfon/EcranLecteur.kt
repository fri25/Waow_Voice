package bj.assistantfon

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Parcours l'arbre d'accessibilite pour extraire les champs de formulaire
 * et leur libelle.
 *
 * Dans une page web (Chrome), le libelle est presque toujours un texte place
 * AU-DESSUS du champ, souvent sous un ancetre commun, pas dans le champ.
 * On cherche donc, en priorite, un texte proche et situe au-dessus.
 */
class EcranLecteur(private val service: AccessibilityService) {

    private val noeuds = mutableListOf<AccessibilityNodeInfo>()

    fun lire(): List<Champ> {
        recycler()
        val racine = service.rootInActiveWindow ?: return emptyList()
        val trouves = mutableListOf<AccessibilityNodeInfo>()
        parcourir(racine, trouves)

        return trouves.mapIndexed { i, n ->
            noeuds.add(n)
            val label = libelleDe(n)
            Champ(index = i, label = label, type = TypeChamp.deviner(label))
        }
    }

    fun noeud(index: Int): AccessibilityNodeInfo? = noeuds.getOrNull(index)

    fun bounds(index: Int): Rect? {
        val node = noeuds.getOrNull(index) ?: return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    fun recycler() {
        noeuds.forEach {
            try {
                it.recycle()
            } catch (_: Exception) {
            }
        }
        noeuds.clear()
    }

    private fun parcourir(node: AccessibilityNodeInfo?, sortie: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (estChampSaisie(node) && node.isVisibleToUser && node.isEnabled) sortie.add(node)
        for (i in 0 until node.childCount) parcourir(node.getChild(i), sortie)
    }

    private fun estChampSaisie(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val classe = node.className?.toString() ?: return false
        return classe.contains("EditText") || classe.contains("TextField")
    }

    private fun libelleDe(node: AccessibilityNodeInfo): String {
        node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return nettoyer(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return nettoyer(it) }

        val champ = Rect()
        node.getBoundsInScreen(champ)

        // On remonte les ancetres et on cherche un texte juste au-dessus du champ.
        var ancetre = node.parent
        var niveau = 0
        while (ancetre != null && niveau < 5) {
            val candidats = mutableListOf<Pair<String, Rect>>()
            for (i in 0 until ancetre.childCount) {
                val enfant = ancetre.getChild(i) ?: continue
                if (estChampSaisie(enfant)) continue
                val texte = (enfant.text?.toString()?.trim().orEmpty())
                    .ifBlank { enfant.contentDescription?.toString()?.trim().orEmpty() }
                if (texte.isNotBlank()) {
                    val r = Rect()
                    enfant.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) candidats.add(texte to r)
                }
            }
            val dessus = candidats
                .filter { (_, r) ->
                    r.bottom <= champ.top + 12 &&
                        r.bottom >= champ.top - 140 &&
                        r.right >= champ.left - 48 &&
                        r.left <= champ.right + 48
                }
                .minByOrNull { champ.top - it.second.bottom }
            if (dessus != null) return nettoyer(dessus.first)

            // A defaut, un texte sur la meme ligne, juste avant le champ.
            val cote = candidats
                .filter { (_, r) ->
                    r.right <= champ.left + 12 &&
                        r.right >= champ.left - 320 &&
                        r.bottom > champ.top && r.top < champ.bottom
                }
                .maxByOrNull { it.second.right }
            if (cote != null) return nettoyer(cote.first)

            ancetre = ancetre.parent
            niveau++
        }

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return nettoyer(it) }
        return ""
    }

    private fun nettoyer(texte: String): String =
        texte.replace(Regex("\\s+"), " ").trim().take(80)
}
