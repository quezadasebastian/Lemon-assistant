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

    fun startListeningForPin(onPinExtracted: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Reconocimiento de voz no disponible.")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Pin Speech recognizer ready.")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "Pin speech error code: $error")
                    onError("No se pudo escuchar el PIN. Por favor intenta de nuevo.")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Pin recognized: $spokenText")
                    val digits = extractSixDigits(spokenText)
                    if (digits != null && digits.length == 6) {
                        onPinExtracted(digits)
                    } else {
                        onError("El PIN debe tener exactamente 6 dígitos. Dijiste: $spokenText")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "PE"))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicta tu PIN de 6 dígitos...")
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
        // 1. Extraemos el teléfono peruano (9 dígitos que empiezan con 9, tolerando guiones y espacios del SpeechRecognizer)
        val (phone, remainingText) = extractPeruvianPhone(text)
        if (phone == null) {
            onError("No se detectó un número celular de 9 dígitos. Dijiste: $text. Por favor indica un teléfono que empiece con 9.")
            return
        }

        // 2. Extraemos el monto del texto restante (soporta 'céntimos', 'un sol con 50', decimales y números)
        val amount = extractAmount(remainingText)
        if (amount == null) {
            onError("No se detectó el monto a enviar para el número $phone. Por favor indica por ejemplo: diez soles, o cincuenta céntimos.")
            return
        }

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

    /**
     * Extrae un número celular peruano de 9 dígitos que comience con 9,
     * admitiendo separadores naturales insertados por el reconocedor de voz
     * como "980-958392", "980 95 8392", "980-958-392" o "980-958392-0.10".
     * Devuelve el teléfono de 9 dígitos limpios y el texto remanente sin el teléfono.
     */
    fun extractPeruvianPhone(text: String): Pair<String?, String> {
        var searchIndex = 0
        while (searchIndex < text.length) {
            val startIndex = text.indexOf('9', searchIndex)
            if (startIndex == -1) break

            // Si el '9' es parte de un número anterior (ej. 19), saltar
            if (startIndex > 0 && text[startIndex - 1].isDigit()) {
                searchIndex = startIndex + 1
                continue
            }

            val digits = StringBuilder()
            var endIndex = startIndex

            while (endIndex < text.length && digits.length < 9) {
                val c = text[endIndex]
                if (c.isDigit()) {
                    digits.append(c)
                } else if (c != ' ' && c != '-' && c != '.') {
                    // Carácter delimitador de palabra no telefónica
                    break
                }
                endIndex++
            }

            if (digits.length == 9) {
                val phone = digits.toString()
                val remaining = text.substring(0, startIndex) + " " + text.substring(endIndex)
                return Pair(phone, remaining)
            }

            searchIndex = startIndex + 1
        }
        return Pair(null, text)
    }

    /**
     * Extrae el monto monetario en Soles a partir de múltiples formas coloquiales y numéricas peruanas:
     * - "10 soles", "20 soles" -> "10.00", "20.00"
     * - "10 céntimos", "50 céntimos" -> "0.10", "0.50"
     * - "un sol con 50 céntimos", "1 sol 50" -> "1.50"
     * - "un sol", "medio sol" -> "1.00", "0.50"
     * - "0.10", "0.1", "1.50", "88.88", "-0.10" -> "0.10", "0.10", "1.50", "88.88", "0.10"
     */
    fun extractAmount(text: String): String? {
        val clean = text.lowercase(Locale("es", "PE")).trim()

        // 1. Caso: solo céntimos (ej. "10 céntimos", "50 centimos", "5 céntimos")
        val centsOnlyRegex = Regex("""\b(\d+)\s*c[eé]ntimos?\b""")
        val centsOnlyMatch = centsOnlyRegex.find(clean)
        if (centsOnlyMatch != null) {
            val cents = centsOnlyMatch.groupValues[1].toIntOrNull()
            if (cents != null) {
                return String.format(Locale.US, "0.%02d", cents)
            }
        }

        // 2. Caso compuesto: soles con céntimos (ej. "un sol con 50 céntimos", "2 soles con 20 céntimos", "1 sol 50")
        val solesWithCentsRegex = Regex("""\b(un|uno|\d+)\s*sol(?:es)?\s*(?:con|y)?\s*(\d+)\s*(?:c[eé]ntimos?)?\b""")
        val solesWithCentsMatch = solesWithCentsRegex.find(clean)
        if (solesWithCentsMatch != null) {
            val solesPart = solesWithCentsMatch.groupValues[1]
            val soles = if (solesPart == "un" || solesPart == "uno") 1 else (solesPart.toIntOrNull() ?: 0)
            val cents = solesWithCentsMatch.groupValues[2].toIntOrNull() ?: 0
            return String.format(Locale.US, "%d.%02d", soles, cents)
        }

        // 3. Caso: "un sol" o "1 sol"
        if (Regex("""\b(un|uno)\s*sol\b""").containsMatchIn(clean)) {
            return "1.00"
        }

        // 4. Caso: "medio sol"
        if (Regex("""\bmedio\s*sol\b""").containsMatchIn(clean)) {
            return "0.50"
        }

        // 5. Palabras para números comunes en español (ej. "cinco soles", "diez soles", "veinte soles")
        val wordNumberMap = mapOf(
            "dos" to "2.00", "tres" to "3.00", "cuatro" to "4.00", "cinco" to "5.00",
            "seis" to "6.00", "siete" to "7.00", "ocho" to "8.00", "nueve" to "9.00",
            "diez" to "10.00", "once" to "11.00", "doce" to "12.00", "quince" to "15.00",
            "veinte" to "20.00", "veinticinco" to "25.00", "treinta" to "30.00",
            "cuarenta" to "40.00", "cincuenta" to "50.00", "cien" to "100.00"
        )
        for ((word, valStr) in wordNumberMap) {
            if (Regex("""\b$word\s*sol(?:es)?\b""").containsMatchIn(clean)) {
                return valStr
            }
        }

        // 6. Número explícito con posible signo menos o punto/coma decimal (ej. "-0.10", "10", "1.50", "88.88")
        val numericRegex = Regex("""(?:^|[^\d])(\d+([.,]\d{1,2})?)\b""")
        val numericMatch = numericRegex.find(clean)
        if (numericMatch != null) {
            val raw = numericMatch.groupValues[1].replace(',', '.')
            return if (raw.contains(".")) {
                val parts = raw.split('.')
                val intP = parts[0].ifEmpty { "0" }
                val decP = parts[1].padEnd(2, '0').take(2)
                "$intP.$decP"
            } else {
                "$raw.00"
            }
        }

        return null
    }

    private fun extractSixDigits(text: String): String? {
        val wordDigitMap = mapOf(
            "cero" to "0", "uno" to "1", "dos" to "2", "tres" to "3",
            "cuatro" to "4", "cinco" to "5", "seis" to "6", "siete" to "7",
            "ocho" to "8", "nueve" to "9"
        )
        val sb = StringBuilder()
        val tokens = text.lowercase(Locale("es", "PE")).replace('-', ' ').split(Regex("""\s+"""))
        for (token in tokens) {
            if (token.all { it.isDigit() }) {
                sb.append(token)
            } else if (wordDigitMap.containsKey(token)) {
                sb.append(wordDigitMap[token])
            }
        }
        val allDigits = sb.toString()
        return if (allDigits.length == 6) allDigits else null
    }

    companion object {
        private const val TAG = "VoiceCommandEngine"
    }
}
