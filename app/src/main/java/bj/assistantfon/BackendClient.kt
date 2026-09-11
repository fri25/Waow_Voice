package bj.assistantfon

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client HTTP minimal (sans dependance externe) vers le backend FastAPI.
 * Contrat du PRD 6.4 :
 *   POST /guide      { champs, index_courant } -> { categorie, audio_url, mode_saisie }
 *   POST /transcrire multipart(audio, categorie) -> { valeur, statut, audio_confirmation_url }
 *
 * Toutes les methodes renvoient null en cas d'echec reseau : l'appelant
 * bascule alors sur le mode demo hors-ligne (SecoursDemo).
 */
class BackendClient(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(CLE_URL, URL_PAR_DEFAUT) ?: URL_PAR_DEFAUT
        set(value) {
            prefs.edit().putString(CLE_URL, value.trim().trimEnd('/')).apply()
        }

    var modeDemoHorsLigne: Boolean
        get() = prefs.getBoolean(CLE_DEMO, false)
        set(value) = prefs.edit().putBoolean(CLE_DEMO, value).apply()

    fun guide(champs: List<Champ>, indexCourant: Int): ReponseGuide? {
        if (modeDemoHorsLigne) return null
        return try {
            val tableau = JSONArray()
            champs.forEach { c ->
                tableau.put(
                    JSONObject()
                        .put("index", c.index)
                        .put("label", c.label)
                        .put("type", c.type)
                )
            }
            val corps = JSONObject()
                .put("champs", tableau)
                .put("index_courant", indexCourant)
                .toString()

            val url = URL("$baseUrl/guide")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val texte = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code !in 200..299 || texte.isNullOrBlank()) {
                Log.w(TAG, "guide HTTP $code")
                return null
            }
            val json = JSONObject(texte)
            ReponseGuide(
                categorie = chaineOuNull(json, "categorie")
                    ?: champs.getOrNull(indexCourant)?.type ?: TypeChamp.TEXTE,
                audioUrl = chaineOuNull(json, "audio_url"),
                modeSaisie = chaineOuNull(json, "mode_saisie") ?: "libre"
            )
        } catch (e: Exception) {
            Log.w(TAG, "guide echec : ${e.message}")
            null
        }
    }

    fun transcrire(audio: File, categorie: String): ReponseTranscription? {
        if (modeDemoHorsLigne) return null
        return try {
            val limite = "----AssistantFon${System.currentTimeMillis()}"
            val url = URL("$baseUrl/transcrire")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 60000
                doOutput = true
                setChunkedStreamingMode(0)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
            }

            DataOutputStream(conn.outputStream).use { out ->
                ecrireChampTexte(out, limite, "categorie", categorie)
                ecrireFichier(out, limite, "audio", audio)
                out.writeBytes("--$limite--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val texte = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code !in 200..299 || texte.isNullOrBlank()) {
                Log.w(TAG, "transcrire HTTP $code")
                return null
            }
            val json = JSONObject(texte)
            ReponseTranscription(
                transcription = chaineOuNull(json, "transcription"),
                valeur = chaineOuNull(json, "valeur"),
                statut = chaineOuNull(json, "statut") ?: "incompris",
                audioConfirmationUrl = chaineOuNull(json, "audio_confirmation_url")
            )
        } catch (e: Exception) {
            Log.w(TAG, "transcrire echec : ${e.message}")
            null
        }
    }

    /** Vision d'usage general : capture d'ecran -> consigne fon. */
    fun comprendre(image: File): ReponseVision? {
        if (modeDemoHorsLigne) return null
        return try {
            val limite = "----AssistantFon${System.currentTimeMillis()}"
            val url = URL("$baseUrl/comprendre")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 120000
                doOutput = true
                setChunkedStreamingMode(0)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
            }

            DataOutputStream(conn.outputStream).use { out ->
                ecrireFichier(out, limite, "image", image, "image/png")
                out.writeBytes("--$limite--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val texte = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code !in 200..299 || texte.isNullOrBlank()) {
                Log.w(TAG, "comprendre HTTP $code")
                return null
            }
            val json = JSONObject(texte)
            ReponseVision(
                statut = chaineOuNull(json, "statut") ?: "incompris",
                description = chaineOuNull(json, "description"),
                champ = chaineOuNull(json, "champ"),
                categorie = chaineOuNull(json, "categorie") ?: TypeChamp.TEXTE,
                fon = chaineOuNull(json, "fon"),
                audioUrl = chaineOuNull(json, "audio_url")
            )
        } catch (e: Exception) {
            Log.w(TAG, "comprendre echec : ${e.message}")
            null
        }
    }

    /** Transforme une URL relative renvoyee par le backend en URL absolue. */
    fun urlAbsolue(u: String?): String? {
        if (u.isNullOrBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return "$baseUrl/${u.trimStart('/')}"
    }

    /** Lit une chaine, en renvoyant null si le champ est absent ou JSON null. */
    private fun chaineOuNull(json: JSONObject, cle: String): String? {
        if (!json.has(cle) || json.isNull(cle)) return null
        return json.optString(cle).ifBlank { null }
    }

    private fun ecrireChampTexte(out: DataOutputStream, limite: String, nom: String, valeur: String) {
        out.writeBytes("--$limite\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$nom\"\r\n\r\n")
        out.writeBytes("$valeur\r\n")
    }

    private fun ecrireFichier(
        out: DataOutputStream,
        limite: String,
        nom: String,
        fichier: File,
        type: String = "audio/wav"
    ) {
        out.writeBytes("--$limite\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$nom\"; filename=\"${fichier.name}\"\r\n")
        out.writeBytes("Content-Type: $type\r\n\r\n")
        BufferedInputStream(FileInputStream(fichier)).use { entree ->
            val tampon = ByteArray(8192)
            var lus: Int
            while (entree.read(tampon).also { lus = it } != -1) {
                out.write(tampon, 0, lus)
            }
        }
        out.writeBytes("\r\n")
    }

    companion object {
        private const val TAG = "BackendClient"
        private const val PREFS = "assistantfon"
        private const val CLE_URL = "base_url"
        private const val CLE_DEMO = "mode_demo"
        const val URL_PAR_DEFAUT = "http://127.0.0.1:8000"
    }
}
