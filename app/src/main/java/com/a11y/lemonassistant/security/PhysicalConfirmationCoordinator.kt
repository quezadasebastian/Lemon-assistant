package com.a11y.lemonassistant.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import com.a11y.lemonassistant.audio.InclusiveTtsManager

class PhysicalConfirmationCoordinator(
    context: Context,
    private val tts: InclusiveTtsManager,
    private val onConfirmed: () -> Unit,
    private val onCancelled: () -> Unit
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var lastVolumeUpTimestamp = 0L
    private val doubleClickThresholdMs = 1300L

    var isAwaitingConfirmation = false

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isAwaitingConfirmation) return false

        // Solo procesamos la pulsación hacia abajo (ACTION_DOWN)
        if (event.action != KeyEvent.ACTION_DOWN) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastVolumeUpTimestamp <= doubleClickThresholdMs) {
                    // ¡Doble pulsación verificada! Autorización concedida
                    isAwaitingConfirmation = false
                    lastVolumeUpTimestamp = 0L

                    triggerConfirmationHaptic()
                    tts.speakUrgent("Confirmación física autorizada. Procesando pago en Lemon Cash.")
                    onConfirmed()
                } else {
                    lastVolumeUpTimestamp = now
                    triggerSingleHaptic()
                    tts.speakUrgent("Presiona subir volumen una vez más para autorizar.")
                }
                return true // Consumir evento para no alterar el volumen multimedia
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                isAwaitingConfirmation = false
                lastVolumeUpTimestamp = 0L

                triggerCancelHaptic()
                tts.speakUrgent("Operación cancelada por el usuario. No se debitó dinero.")
                onCancelled()
                return true // Consumir evento
            }
        }

        return false
    }

    private fun triggerConfirmationHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 150, 80, 250)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(400)
        }
    }

    private fun triggerSingleHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60)
        }
    }

    private fun triggerCancelHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 300, 100, 300)
            val amplitudes = intArrayOf(0, 200, 0, 200)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }
}
