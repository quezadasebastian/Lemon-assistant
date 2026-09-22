package com.a11y.lemonassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.a11y.lemonassistant.audio.InclusiveTtsManager
import com.a11y.lemonassistant.fsm.TransferParams
import com.a11y.lemonassistant.fsm.TransferStateMachine
import com.a11y.lemonassistant.security.PhysicalConfirmationCoordinator

class LemonA11yTransferService : AccessibilityService() {

    private lateinit var actionHelper: A11yActionHelper
    private lateinit var keypadNavigator: KeypadNavigator
    private lateinit var screenInspector: ScreenInspector
    private lateinit var tts: InclusiveTtsManager
    private lateinit var physicalCoordinator: PhysicalConfirmationCoordinator
    private lateinit var stateMachine: TransferStateMachine

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "LemonA11yTransferService connected and initialized.")

        tts = InclusiveTtsManager(this)
        actionHelper = A11yActionHelper(this)
        keypadNavigator = KeypadNavigator(actionHelper)
        screenInspector = ScreenInspector(actionHelper)

        physicalCoordinator = PhysicalConfirmationCoordinator(
            context = this,
            tts = tts,
            onConfirmed = {
                val root = rootInActiveWindow
                stateMachine.releaseFinalPayment(root)
                root?.recycle()
            },
            onCancelled = {
                stateMachine.abortTransfer("Cancelado por el usuario mediante botón físico.")
            }
        )

        stateMachine = TransferStateMachine(
            actionHelper = actionHelper,
            keypadNavigator = keypadNavigator,
            screenInspector = screenInspector,
            tts = tts,
            physicalCoordinator = physicalCoordinator
        )

        tts.speak("Servicio de accesibilidad para Lemon Cash activado.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()
        if (pkg == "com.applemoncash") {
            val root = rootInActiveWindow
            stateMachine.onRootNodeUpdated(root)
            root?.recycle()
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val handled = physicalCoordinator.handleKeyEvent(event)
        return handled || super.onKeyEvent(event)
    }

    fun startTransfer(params: TransferParams) {
        stateMachine.startTransfer(params)

        // Abrir Lemon Cash en primer plano si no está abierta
        val launchIntent = packageManager.getLaunchIntentForPackage("com.applemoncash")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            tts.speakUrgent("No se encontró la aplicación Lemon Cash instalada.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::tts.isInitialized) {
            tts.shutdown()
        }
        Log.i(TAG, "LemonA11yTransferService destroyed.")
    }

    override fun onInterrupt() {
        Log.w(TAG, "LemonA11yTransferService interrupted.")
    }

    companion object {
        private const val TAG = "LemonA11yService"
        var instance: LemonA11yTransferService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }
}
