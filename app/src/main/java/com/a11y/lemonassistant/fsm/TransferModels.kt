package com.a11y.lemonassistant.fsm

data class TransferParams(
    val loginPin: String,
    val recipientPhone: String,
    val amount: String,
    val currency: String = "Soles",
    val destinationNetwork: String = "YAPE",
    val requireManualConfirm: Boolean = true
)

data class TransferSummary(
    val amount: String,
    val currency: String,
    val registeredHolder: String,
    val recipientPhone: String,
    val destinationNetwork: String,
    val commission: String = "Gratis"
)

data class ReceiptData(
    val title: String = "¡Lemoneaste!",
    val operationNumber: String,
    val timestamp: String,
    val amount: String,
    val recipient: String
)

sealed class TransferState {
    object Idle : TransferState()
    object AuthenticatingPin : TransferState()
    object NavigatingDashboard : TransferState()
    object SelectingCurrency : TransferState()
    object SelectingChannel : TransferState()
    data class EnteringRecipient(val phone: String) : TransferState()
    data class SelectingBank(val targetBank: String) : TransferState()
    data class ConfiguringAmount(val amount: String) : TransferState()
    data class AwaitingUserConfirmation(val summary: TransferSummary) : TransferState()
    object ExecutingFinalConfirmation : TransferState()
    data class Completed(val receipt: ReceiptData) : TransferState()
    data class Failed(val reason: String) : TransferState()
}
