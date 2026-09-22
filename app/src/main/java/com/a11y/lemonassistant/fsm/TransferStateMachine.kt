package com.a11y.lemonassistant.fsm

import android.view.accessibility.AccessibilityNodeInfo
import com.a11y.lemonassistant.audio.InclusiveTtsManager
import com.a11y.lemonassistant.security.PhysicalConfirmationCoordinator
import com.a11y.lemonassistant.service.A11yActionHelper
import com.a11y.lemonassistant.service.DetectedScreen
import com.a11y.lemonassistant.service.KeypadNavigator
import com.a11y.lemonassistant.service.ScreenInspector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TransferStateMachine(
    private val actionHelper: A11yActionHelper,
    private val keypadNavigator: KeypadNavigator,
    private val screenInspector: ScreenInspector,
    private val tts: InclusiveTtsManager,
    private val physicalCoordinator: PhysicalConfirmationCoordinator
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var currentState: TransferState = TransferState.Idle
        private set

    var currentParams: TransferParams? = null
        private set

    private var isExecutingAction = false
    private var hasEnteredAmount = false

    fun startTransfer(params: TransferParams) {
        currentParams = params
        hasEnteredAmount = false
        currentState = TransferState.AuthenticatingPin
        tts.speakUrgent("Iniciando asistente de transferencia accesible en Lemon Cash.")
    }

    fun abortTransfer(reason: String) {
        currentState = TransferState.Failed(reason)
        hasEnteredAmount = false
        physicalCoordinator.isAwaitingConfirmation = false
        tts.speakUrgent("Transferencia detenida: $reason")
    }

    fun onRootNodeUpdated(root: AccessibilityNodeInfo?) {
        if (root == null || isExecutingAction) return
        val params = currentParams ?: return

        val detected = screenInspector.identifyScreen(root)

        scope.launch {
            when (currentState) {
                is TransferState.AuthenticatingPin -> {
                    if (detected == DetectedScreen.PIN_ENTRY) {
                        isExecutingAction = true
                        tts.speak("Autenticando clave de acceso.")
                        val success = keypadNavigator.enterNumericSequence(root, params.loginPin)
                        isExecutingAction = false
                        if (success) {
                            currentState = TransferState.NavigatingDashboard
                        } else {
                            abortTransfer("No se pudo ingresar el PIN numérico.")
                        }
                    } else if (detected == DetectedScreen.DASHBOARD) {
                        currentState = TransferState.NavigatingDashboard
                    }
                }

                is TransferState.NavigatingDashboard -> {
                    if (detected == DetectedScreen.DASHBOARD) {
                        isExecutingAction = true
                        tts.speak("Accediendo a la sección Enviar.")
                        val sendButton = actionHelper.findFirstByTextOrDesc(root, "Enviar")
                        val clicked = actionHelper.performSmartClick(sendButton)
                        sendButton?.recycle()
                        isExecutingAction = false
                        if (clicked) {
                            currentState = TransferState.SelectingCurrency
                        }
                    } else if (detected == DetectedScreen.CURRENCY_SELECTION) {
                        currentState = TransferState.SelectingCurrency
                    }
                }

                is TransferState.SelectingCurrency -> {
                    if (detected == DetectedScreen.CURRENCY_SELECTION) {
                        isExecutingAction = true
                        tts.speak("Seleccionando moneda ${params.currency}.")
                        val currencyNode = actionHelper.findFirstByTextOrDesc(root, params.currency)
                        val clicked = actionHelper.performSmartClick(currencyNode)
                        currencyNode?.recycle()
                        isExecutingAction = false
                        if (clicked) {
                            currentState = TransferState.SelectingChannel
                        }
                    } else if (detected == DetectedScreen.CHANNEL_SELECTION) {
                        currentState = TransferState.SelectingChannel
                    }
                }

                is TransferState.SelectingChannel -> {
                    if (detected == DetectedScreen.CHANNEL_SELECTION) {
                        isExecutingAction = true
                        tts.speak("Seleccionando envío por número telefónico.")
                        val phoneChannelNode = actionHelper.findFirstByTextOrDesc(root, "Número de teléfono")
                        val clicked = actionHelper.performSmartClick(phoneChannelNode)
                        phoneChannelNode?.recycle()
                        isExecutingAction = false
                        if (clicked) {
                            currentState = TransferState.EnteringRecipient(params.recipientPhone)
                        }
                    } else if (detected == DetectedScreen.RECIPIENT_INPUT) {
                        currentState = TransferState.EnteringRecipient(params.recipientPhone)
                    }
                }

                is TransferState.EnteringRecipient -> {
                    if (detected == DetectedScreen.RECIPIENT_INPUT) {
                        isExecutingAction = true
                        tts.speak("Ingresando teléfono del destinatario.")
                        val inputNode = actionHelper.findNodeRecursively(root) {
                            it.className?.toString()?.contains("EditText") == true ||
                                    it.isEditable ||
                                    it.text?.toString()?.contains("Número") == true
                        }

                        if (inputNode != null) {
                            actionHelper.setTextSafely(inputNode, params.recipientPhone)
                            inputNode.recycle()
                            delay(300)

                            val searchButton = actionHelper.findFirstByTextOrDesc(root, "Buscar")
                            actionHelper.performSmartClick(searchButton)
                            searchButton?.recycle()

                            currentState = TransferState.SelectingBank(params.destinationNetwork)
                        }
                        isExecutingAction = false
                    }
                }

                is TransferState.SelectingBank -> {
                    if (detected == DetectedScreen.BANK_MODAL) {
                        isExecutingAction = true
                        tts.speak("Seleccionando entidad ${params.destinationNetwork}.")
                        val bankNode = actionHelper.findFirstByTextOrDesc(root, params.destinationNetwork)
                        val clicked = actionHelper.performSmartClick(bankNode)
                        bankNode?.recycle()
                        isExecutingAction = false
                        if (clicked) {
                            currentState = TransferState.ConfiguringAmount(params.amount)
                        }
                    } else if (detected == DetectedScreen.AMOUNT_ENTRY) {
                        currentState = TransferState.ConfiguringAmount(params.amount)
                    }
                }

                is TransferState.ConfiguringAmount -> {
                    if (detected == DetectedScreen.AMOUNT_ENTRY && !hasEnteredAmount) {
                        isExecutingAction = true
                        hasEnteredAmount = true
                        tts.speak("Configurando monto de ${params.amount} soles.")

                        val sequence = buildKeystrokeSequence(params.amount)
                        keypadNavigator.enterNumericSequence(root, sequence)
                        delay(500)

                        val continueButton = actionHelper.findFirstByTextOrDesc(root, "Continuar")
                        actionHelper.performSmartClick(continueButton)
                        continueButton?.recycle()
                        isExecutingAction = false

                        currentState = TransferState.SubmittingAmount
                    } else if (detected == DetectedScreen.CONFIRMATION_AUDIT) {
                        // Safety Gate 3: Hard Stop mandatorio
                        val summary = screenInspector.extractAuditSummary(
                            root,
                            params.recipientPhone,
                            params.amount
                        )
                        currentState = TransferState.AwaitingUserConfirmation(summary)
                        physicalCoordinator.isAwaitingConfirmation = true
                        tts.speakTransferAuditSummary(summary)
                    }
                }

                is TransferState.SubmittingAmount -> {
                    if (detected == DetectedScreen.AMOUNT_ENTRY) {
                        // El monto ya fue digitado exactamente una vez.
                        // Solo reintentamos hacer clic en Continuar si la pantalla no avanzó
                        if (!isExecutingAction) {
                            isExecutingAction = true
                            delay(600)
                            val continueButton = actionHelper.findFirstByTextOrDesc(root, "Continuar")
                            actionHelper.performSmartClick(continueButton)
                            continueButton?.recycle()
                            isExecutingAction = false
                        }
                    } else if (detected == DetectedScreen.CONFIRMATION_AUDIT) {
                        val summary = screenInspector.extractAuditSummary(
                            root,
                            params.recipientPhone,
                            params.amount
                        )
                        currentState = TransferState.AwaitingUserConfirmation(summary)
                        physicalCoordinator.isAwaitingConfirmation = true
                        tts.speakTransferAuditSummary(summary)
                    }
                }

                is TransferState.AwaitingUserConfirmation -> {
                    // Estado bloqueado por diseño. Esperando exclusivamente el evento físico (onConfirmed)
                }

                is TransferState.ExecutingFinalConfirmation -> {
                    if (detected == DetectedScreen.CONFIRMATION_AUDIT) {
                        isExecutingAction = true
                        tts.speak("Enviando transferencia.")
                        val confirmBtn = actionHelper.findFirstByTextOrDesc(root, "Confirmar envío")
                        actionHelper.performSmartClick(confirmBtn)
                        confirmBtn?.recycle()
                        isExecutingAction = false
                    } else if (detected == DetectedScreen.RECEIPT_SUCCESS) {
                        val receipt = screenInspector.extractReceiptData(root)
                        currentState = TransferState.Completed(receipt)
                        tts.speakUrgent("¡Transferencia completada con éxito! Comprobante emitido.")
                    }
                }

                is TransferState.Completed, is TransferState.Failed, is TransferState.Idle -> {
                    // Estados terminales
                }
            }
        }
    }

    fun releaseFinalPayment(root: AccessibilityNodeInfo?) {
        currentState = TransferState.ExecutingFinalConfirmation
        if (root != null) {
            val confirmBtn = actionHelper.findFirstByTextOrDesc(root, "Confirmar envío")
            actionHelper.performSmartClick(confirmBtn)
            confirmBtn?.recycle()
        }
    }

    fun buildKeystrokeSequence(amountStr: String): String {
        val clean = amountStr.trim().replace(',', '.')
        val doubleVal = clean.toDoubleOrNull() ?: return clean

        // Si es un número entero exacto (ej. 5, 5.0, 5.00, 10, 20.00), enviamos solo el entero ("5", "10", "20")
        if (doubleVal % 1.0 == 0.0) {
            return doubleVal.toLong().toString()
        }

        // Si tiene decimales reales (ej. 0.1, 0.10, 0.14, 88.88), desglosar en parte entera, punto y decimales
        val parts = clean.split('.')
        val integerPart = parts[0].ifEmpty { "0" }
        val decimalPart = if (parts.size > 1) parts[1] else ""
        return "$integerPart.$decimalPart"
    }
}
