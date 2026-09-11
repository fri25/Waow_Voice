package bj.assistantfon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Capture micro en WAV mono 16 kHz (format attendu par MMS-1b-all cote backend,
 * cf. PRD 6.3). Ecrit l'entete WAV une fois l'enregistrement termine.
 */
class EnregistreurAudio(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var fil: Thread? = null
    private var fichier: File? = null

    @Volatile
    private var enCours = false

    val estEnCours: Boolean get() = enCours

    fun demarrer(): Boolean {
        if (enCours) return false

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Permission RECORD_AUDIO non accordee")
            return false
        }

        val minTampon = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minTampon <= 0) {
            Log.w(TAG, "getMinBufferSize a echoue")
            return false
        }
        val tailleTampon = minTampon * 2

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(tailleTampon)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord impossible : ${e.message}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.w(TAG, "AudioRecord non initialise")
            return false
        }

        val sortie = File(context.cacheDir, "reponse_${System.currentTimeMillis()}.wav")
        audioRecord = record
        fichier = sortie
        enCours = true

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startRecording : ${e.message}")
            enCours = false
            record.release()
            audioRecord = null
            return false
        }

        fil = Thread {
            RandomAccessFile(sortie, "rw").use { raf ->
                raf.setLength(0)
                raf.write(enteteWav(0))

                val tampon = ByteArray(tailleTampon)
                while (enCours) {
                    val lus = record.read(tampon, 0, tampon.size)
                    if (lus > 0) raf.write(tampon, 0, lus)
                }

                val tailleDonnees = (raf.length() - ENTETE_OCTETS).coerceAtLeast(0).toInt()
                raf.seek(0)
                raf.write(enteteWav(tailleDonnees))
            }
        }.also { it.start() }

        return true
    }

    /** Arrete la capture et renvoie le fichier WAV, ou null s'il est vide. */
    fun arreter(): File? {
        if (!enCours) return null
        enCours = false

        try {
            fil?.join(2000)
        } catch (_: InterruptedException) {
        }

        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        audioRecord = null
        fil = null

        val sortie = fichier
        fichier = null
        return if (sortie != null && sortie.length() > ENTETE_OCTETS) sortie else null
    }

    private fun enteteWav(tailleDonnees: Int): ByteArray {
        val octets = ByteArray(ENTETE_OCTETS)
        var p = 0

        fun ecrire(texte: String) {
            texte.toByteArray(Charsets.US_ASCII).copyInto(octets, p)
            p += texte.length
        }

        fun ecrireEntierLE(v: Int) {
            octets[p++] = (v and 0xff).toByte()
            octets[p++] = ((v shr 8) and 0xff).toByte()
            octets[p++] = ((v shr 16) and 0xff).toByte()
            octets[p++] = ((v shr 24) and 0xff).toByte()
        }

        fun ecrireCourtLE(v: Int) {
            octets[p++] = (v and 0xff).toByte()
            octets[p++] = ((v shr 8) and 0xff).toByte()
        }

        val canaux = 1
        val bitsParEchantillon = 16
        val octetsParEchantillon = canaux * bitsParEchantillon / 8

        ecrire("RIFF")
        ecrireEntierLE(36 + tailleDonnees)
        ecrire("WAVE")
        ecrire("fmt ")
        ecrireEntierLE(16)
        ecrireCourtLE(1) // PCM
        ecrireCourtLE(canaux)
        ecrireEntierLE(SAMPLE_RATE)
        ecrireEntierLE(SAMPLE_RATE * octetsParEchantillon)
        ecrireCourtLE(octetsParEchantillon)
        ecrireCourtLE(bitsParEchantillon)
        ecrire("data")
        ecrireEntierLE(tailleDonnees)
        return octets
    }

    companion object {
        private const val TAG = "EnregistreurAudio"
        private const val SAMPLE_RATE = 16000
        private const val ENTETE_OCTETS = 44
    }
}
