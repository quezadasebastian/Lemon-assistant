package com.a11y.lemonassistant.service

import android.view.accessibility.AccessibilityNodeInfo
import com.a11y.lemonassistant.fsm.ReceiptData
import com.a11y.lemonassistant.fsm.TransferSummary

enum class DetectedScreen {
    UNKNOWN,
    PIN_ENTRY,
    DASHBOARD,
    CURRENCY_SELECTION,
    CHANNEL_SELECTION,
    RECIPIENT_INPUT,
    BANK_MODAL,
    AMOUNT_ENTRY,
    CONFIRMATION_AUDIT,
    RECEIPT_SUCCESS
}

class ScreenInspector(private val actionHelper: A11yActionHelper) {

    fun identifyScreen(root: AccessibilityNodeInfo?): DetectedScreen {
        if (root == null) return DetectedScreen.UNKNOWN

        if (actionHelper.findFirstByTextOrDesc(root, "Ingresa tu clave") != null) {
            return DetectedScreen.PIN_ENTRY
        }

        if (actionHelper.findFirstByTextOrDesc(root, "Confirma tu envío") != null) {
            return DetectedScreen.CONFIRMATION_AUDIT
        }

        if (actionHelper.findFirstByTextOrDesc(root, "¡Lemoneaste!") != null ||
            actionHelper.findFirstByTextOrDesc(root, "N° de operación") != null
        ) {
            return DetectedScreen.RECEIPT_SUCCESS
        }

        if (actionHelper.findFirstByTextOrDesc(root, "¿A qué cuenta de banco") != null) {
            return DetectedScreen.BANK_MODAL
        }

        if (actionHelper.findFirstByTextOrDesc(root, "Enviar soles a un celular") != null ||
            actionHelper.findFirstByTextOrDesc(root, "Buscar") != null &&
            actionHelper.findFirstByTextOrDesc(root, "Número de teléfono") != null
        ) {
            return DetectedScreen.RECIPIENT_INPUT
        }

        if (actionHelper.findFirstByTextOrDesc(root, "Seleccioná cómo enviar") != null) {
            return DetectedScreen.CHANNEL_SELECTION
        }

        if (actionHelper.findFirstByTextOrDesc(root, "¿Qué quieres enviar?") != null) {
            return DetectedScreen.CURRENCY_SELECTION
        }

        if (actionHelper.findFirstByTextOrDesc(root, "Enviar soles") != null &&
            actionHelper.findFirstByTextOrDesc(root, "Continuar") != null
        ) {
            return DetectedScreen.AMOUNT_ENTRY
        }

        if (actionHelper.findFirstByTextOrDesc(root, "Enviar") != null &&
            (actionHelper.findFirstByTextOrDesc(root, "Inicio") != null ||
             actionHelper.findFirstByTextOrDesc(root, "Billetera") != null)
        ) {
            return DetectedScreen.DASHBOARD
        }

        return DetectedScreen.UNKNOWN
    }

    /**
     * Extrae de forma exhaustiva los 6 campos del Safety Gate 3 en la pantalla 'Confirma tu envío':
     * - Monto a transferir
     * - Moneda
     * - Titular registrado en el banco (directorio Yape)
     * - Teléfono de destino
     * - Entidad destino (Yape / Lemon)
     * - Comisión (Gratis)
     */
    fun extractAuditSummary(root: AccessibilityNodeInfo?, fallbackPhone: String, fallbackAmount: String): TransferSummary {
        val allTexts = mutableListOf<String>()
        collectAllVisibleTexts(root, allTexts)

        var amount = fallbackAmount
        var currency = "Soles"
        var registeredHolder = "Titular no especificado"
        var phone = fallbackPhone
        var destinationNetwork = "Yape"
        var commission = "Gratis"

        for (i in 0 until allTexts.size) {
            val text = allTexts[i]

            if (text.contains("Envías", ignoreCase = true) || text.contains("PEN", ignoreCase = true)) {
                val match = Regex("""(PEN|S/\.|\$)?\s*([0-9]+[.,][0-9]{2})""").find(text)
                if (match != null) {
                    amount = match.groupValues[2]
                }
            }

            if (text.contains("Destinatario", ignoreCase = true) || text.contains("Para", ignoreCase = true)) {
                if (i + 1 < allTexts.size && allTexts[i + 1].length > 3) {
                    registeredHolder = allTexts[i + 1]
                }
            }

            if (text.contains("+51") || text.matches(Regex(""".*9[0-9]{8}.*"""))) {
                val match = Regex("""(\+?51)?\s*(9[0-9]{8})""").find(text)
                if (match != null) {
                    phone = match.groupValues[2]
                }
            }

            if (text.contains("Yape", ignoreCase = true)) {
                destinationNetwork = "Yape"
            } else if (text.contains("Lemon", ignoreCase = true)) {
                destinationNetwork = "Lemon"
            }

            if (text.contains("Comisión", ignoreCase = true) && i + 1 < allTexts.size) {
                commission = allTexts[i + 1]
            }
        }

        // Si el titular no se encontró con la etiqueta, buscar cadenas en mayúsculas de más de 2 palabras
        if (registeredHolder == "Titular no especificado") {
            val possibleName = allTexts.firstOrNull { 
                it.split(" ").size >= 2 && it.all { ch -> ch.isLetter() || ch.isWhitespace() } && it.length > 8
            }
            if (possibleName != null) {
                registeredHolder = possibleName
            }
        }

        return TransferSummary(
            amount = amount,
            currency = currency,
            registeredHolder = registeredHolder,
            recipientPhone = phone,
            destinationNetwork = destinationNetwork,
            commission = commission
        )
    }

    fun extractReceiptData(root: AccessibilityNodeInfo?): ReceiptData {
        val allTexts = mutableListOf<String>()
        collectAllVisibleTexts(root, allTexts)

        var operationNumber = "Pendiente"
        var timestamp = ""

        for (i in 0 until allTexts.size) {
            val t = allTexts[i]
            if (t.contains("N° de operación", ignoreCase = true) || t.contains("Operación", ignoreCase = true)) {
                if (i + 1 < allTexts.size) {
                    operationNumber = allTexts[i + 1]
                }
            }
            if (t.contains(":", ignoreCase = true) && (t.contains("202") || t.contains("am") || t.contains("pm"))) {
                timestamp = t
            }
        }

        return ReceiptData(
            operationNumber = operationNumber,
            timestamp = timestamp,
            amount = "",
            recipient = ""
        )
    }

    private fun collectAllVisibleTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrBlank()) list.add(text)
        if (!desc.isNullOrBlank() && desc != text) list.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllVisibleTexts(child, list)
            child.recycle()
        }
    }
}
