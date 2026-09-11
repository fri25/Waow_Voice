package bj.assistantfon

import java.text.Normalizer

/** Un champ de formulaire detecte a l'ecran. */
data class Champ(
    val index: Int,
    val label: String,
    val type: String
)

/** Reponse de POST /guide. */
data class ReponseGuide(
    val categorie: String,
    val audioUrl: String?,
    val modeSaisie: String
)

/** Reponse de POST /transcrire. */
data class ReponseTranscription(
    val transcription: String?,
    val valeur: String?,
    val statut: String,
    val audioConfirmationUrl: String?
)

/**
 * Types de champs du PRD (annexe). Sert au routage cote backend
 * (vocabulaire ferme vs texte libre).
 */
object TypeChamp {

    const val NOM = "nom"
    const val PRENOM = "prenom"
    const val DATE_JOUR = "date_jour"
    const val DATE_MOIS = "date_mois"
    const val DATE_ANNEE = "date_annee"
    const val DATE = "date"
    const val LIEU = "lieu"
    const val TELEPHONE = "telephone"
    const val ADRESSE = "adresse"
    const val PROFESSION = "profession"
    const val CNSS = "cnss"
    const val PIECE = "piece"
    const val SEXE = "sexe"
    const val MATRIMONIAL = "matrimonial"
    const val EMAIL = "email"
    const val NOMBRE = "nombre"
    const val TEXTE = "texte"
    const val OUI_NON = "oui_non"

    private fun sansAccent(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Devine le type a partir du libelle affiche, dans l'ordre du plus specifique au plus general. */
    fun deviner(libelle: String): String {
        val l = sansAccent(libelle)
        if (l.isBlank()) return TEXTE

        return when {
            l.contains("cnss") || l.contains("securite sociale") -> CNSS
            l.contains("piece") || l.contains("identite") || l.contains("cni") ||
                l.contains("passeport") || l.contains("carte") -> PIECE
            l.contains("email") || l.contains("mail") || l.contains("courriel") -> EMAIL
            l.contains("telephone") || l.contains("tel") || l.contains("phone") ||
                l.contains("mobile") || l.contains("portable") -> TELEPHONE

            l.contains("sexe") || l.contains("genre") -> SEXE
            l.contains("matrimoni") || l.contains("marie") || l.contains("celibataire") ||
                l.contains("conjoint") -> MATRIMONIAL

            // Date : on decoupe en 3 sous-questions (PRD 6.4)
            l.contains("jour") && (l.contains("naissance") || l.contains("date")) -> DATE_JOUR
            l.contains("mois") -> DATE_MOIS
            l.contains("annee") -> DATE_ANNEE
            l.contains("date") -> DATE

            l.contains("prenom") -> PRENOM
            l.contains("nom") -> NOM

            l.contains("lieu") -> LIEU
            l.contains("adresse") || l.contains("quartier") || l.contains("rue") ||
                l.contains("ville") || l.contains("commune") -> ADRESSE
            l.contains("profession") || l.contains("metier") || l.contains("emploi") -> PROFESSION

            l.contains("numero") || l.contains("nombre") || l.contains("montant") -> NOMBRE

            else -> TEXTE
        }
    }
}

/**
 * Secours hors-ligne : le PRD (risques) demande une demo demonstrable
 * meme sans reseau. Consignes en francais simple faute de fon embarque,
 * et valeurs factices pour pouvoir derouler la boucle complete.
 */
object SecoursDemo {

    fun instruction(type: String): String = when (type) {
        TypeChamp.NOM -> "Ici, dis-moi ton nom de famille. Appuie longuement et parle."
        TypeChamp.PRENOM -> "Ici, dis-moi ton prenom. Appuie longuement et parle."
        TypeChamp.DATE_JOUR -> "Dis-moi le jour de ta naissance."
        TypeChamp.DATE_MOIS -> "Dis-moi le mois de ta naissance."
        TypeChamp.DATE_ANNEE -> "Dis-moi l'annee de ta naissance."
        TypeChamp.DATE -> "Dis-moi ta date de naissance."
        TypeChamp.LIEU -> "Dis-moi ton lieu de naissance."
        TypeChamp.TELEPHONE -> "Dis-moi ton numero, un chiffre apres l'autre."
        TypeChamp.ADRESSE -> "Dis-moi ton adresse ou ton quartier."
        TypeChamp.PROFESSION -> "Dis-moi ton metier."
        TypeChamp.CNSS -> "Dis-moi ton numero CNSS, un chiffre apres l'autre."
        TypeChamp.PIECE -> "Dis-moi ton numero de piece d'identite."
        TypeChamp.SEXE -> "Dis-moi si tu es un homme ou une femme."
        TypeChamp.MATRIMONIAL -> "Dis-moi ta situation matrimoniale."
        TypeChamp.EMAIL -> "Dicte ton adresse email."
        TypeChamp.NOMBRE -> "Dis-moi ce nombre, un chiffre apres l'autre."
        else -> "Reponds pour ce champ."
    }

    fun reponseIncomprise(): String = "Je n'ai pas bien entendu, repete."

    fun confirmation(valeur: String): String = "J'ai ecrit : $valeur. C'est bon ?"

    fun fin(): String = "Tu as fini, c'est bon."

    fun valeur(type: String): String = when (type) {
        TypeChamp.NOM -> "AGBODJAN"
        TypeChamp.PRENOM -> "Awa"
        TypeChamp.DATE_JOUR -> "12"
        TypeChamp.DATE_MOIS -> "03"
        TypeChamp.DATE_ANNEE -> "1994"
        TypeChamp.DATE -> "12/03/1994"
        TypeChamp.LIEU -> "Cotonou"
        TypeChamp.TELEPHONE -> "0197453210"
        TypeChamp.ADRESSE -> "Quartier Dantokpa, Cotonou"
        TypeChamp.PROFESSION -> "Commercante"
        TypeChamp.CNSS -> "97453210"
        TypeChamp.PIECE -> "1203456789"
        TypeChamp.SEXE -> "Feminin"
        TypeChamp.MATRIMONIAL -> "Celibataire"
        TypeChamp.EMAIL -> "awa.agbodjan@example.com"
        TypeChamp.OUI_NON -> "oui"
        else -> "Reponse"
    }
}
