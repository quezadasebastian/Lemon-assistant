package com.a11y.lemonassistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.a11y.lemonassistant.fsm.TransferSummary
import java.util.Locale

class InclusiveTtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private val pendingUtterances = mutableListOf<Pair<String, Int>>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "PE"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale("es", "ES")
            }
            tts.setSpeechRate(0.92f)
            isInitialized = true

            // Procesar locuciones encoladas antes de que iniciara el motor
            pendingUtterances.forEach { (text, queueMode) ->
                speak(text, queueMode)
            }
            pendingUtterances.clear()
            Log.d(TAG, "Inclusive TTS engine initialized successfully.")
        } else {
            Log.e(TAG, "Failed to initialize TTS engine. Status: $status")
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized) {
            pendingUtterances.add(text to queueMode)
            return
        }
        tts.speak(text, queueMode, null, "UTTERANCE_${System.currentTimeMillis()}")
    }

    fun speakUrgent(text: String) {
        speak(text, TextToSpeech.QUEUE_FLUSH)
    }

    fun speakPhoneFormatted(phone: String): String {
        val clean = phone.replace(Regex("[^0-9]"), "")
        return clean.chunked(3).joinToString(separator = ", ") { chunk ->
            chunk.map { it.toString() }.joinToString(" ")
        }
    }

    fun speakTransferAuditSummary(summary: TransferSummary) {
        val formattedPhone = speakPhoneFormatted(summary.recipientPhone)
        val speech = buildString {
            append("Atención. Por favor verifica los datos de la transferencia en voz alta. ")
            append("Monto a enviar: ${summary.amount} ${summary.currency}. ")
            append("Titular destinatario registrado: ${summary.registeredHolder}. ")
            append("Número telefónico: $formattedPhone. ")
            append("Entidad financiera de destino: ${summary.destinationNetwork}. ")
            append("Comisión bancaria: ${summary.commission}. ")
            append("Para confirmar y autorizar el pago de forma segura, presiona dos veces seguidas el botón de subir volumen. ")
            append("Para cancelar y no debitar dinero, presiona el botón de bajar volumen.")
        }
        speakUrgent(speech)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "InclusiveTtsManager"
    }
}
