package com.a11y.lemonassistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.a11y.lemonassistant.fsm.TransferParams
import java.util.Locale

class VoiceCommandEngine(
    private val context: Context,
    private val onParamsExtracted: (TransferParams) -> Unit,
    private val onError: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null

    fun startListening(loginPin: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Reconocimiento de voz no disponible en este dispositivo.")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Speech recognizer ready.")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "Speech recognition error code: $error")
                    onError("No se pudo reconocer la voz. Código: $error")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Recognized: $spokenText")
                    parseVoiceInput(spokenText, loginPin)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "PE"))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Indica el monto y número de destino...")
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun parseVoiceInput(text: String, loginPin: String) {
        // Ejemplo de entrada: "Enviar 10 soles al 912345678 por Yape"
        val phoneMatch = Regex("""\b(9\d{8})\b""").find(text)
        val amountMatch = Regex("""\b(\d+([.,]\d{1,2})?)\b""").find(text)

        if (phoneMatch == null) {
            onError("No se detectó un número telefónico de 9 dígitos. Por favor indica el celular de destino.")
            return
        }
        if (amountMatch == null) {
            onError("No se detectó el monto a transferir. Por favor indica el monto a enviar.")
            return
        }

        val phone = phoneMatch.value
        val rawAmount = amountMatch.value.replace(',', '.')
        val amount = if (rawAmount.contains(".")) rawAmount else "$rawAmount.00"

        val network = if (text.contains("lemon", ignoreCase = true)) "Lemon" else "YAPE"

        val params = TransferParams(
            loginPin = loginPin,
            recipientPhone = phone,
            amount = amount,
            currency = "Soles",
            destinationNetwork = network,
            requireManualConfirm = true
        )

        onParamsExtracted(params)
    }

    companion object {
        private const val TAG = "VoiceCommandEngine"
    }
}
