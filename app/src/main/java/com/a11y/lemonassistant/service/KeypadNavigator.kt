package com.a11y.lemonassistant.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

class KeypadNavigator(private val actionHelper: A11yActionHelper) {

    suspend fun enterNumericSequence(
        rootNode: AccessibilityNodeInfo,
        sequence: String
    ): Boolean {
        for (char in sequence) {
            val charStr = char.toString()
            // Obtenemos el árbol de accesibilidad más reciente para evitar nodos desvinculados
            val currentRoot = actionHelper.getRootInActiveWindow() ?: rootNode
            val keyNode = findKeypadDigitNode(currentRoot, charStr)

            if (keyNode != null) {
                actionHelper.performSmartClick(keyNode)
                keyNode.recycle()
                delay(220) // Inter-digit debounce óptimo para que React Native procese el estado
            } else {
                // Fallback de coordenadas si la jerarquía semántica no expone el nodo
                val clickedByCoords = clickKeypadByCalculatedCoordinates(currentRoot, charStr)
                if (clickedByCoords) {
                    delay(220)
                } else {
                    if (currentRoot != rootNode) currentRoot.recycle()
                    return false
                }
            }

            if (currentRoot != rootNode) {
                currentRoot.recycle()
            }
        }
        return true
    }

    fun findKeypadDigitNode(root: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val isDot = (digit == "." || digit == ",")

        // Nivel 1: Búsqueda semántica por resource-id nativo de Lemon Cash
        // En Lemon Cash:
        // Teclas 0-9: resource-id "key-0", "key-1", ..., "key-9"
        // Tecla punto: resource-id "key-" (sin texto ni contentDescription)
        val resourceMatch = actionHelper.findNodeRecursively(root) { node ->
            val resId = node.viewIdResourceName
            if (resId != null) {
                if (isDot) {
                    resId.endsWith("key-") || resId.endsWith("key-.") || resId.endsWith("key-dot") || resId.endsWith("key-comma")
                } else {
                    resId.endsWith("key-$digit")
                }
            } else {
                false
            }
        }
        if (resourceMatch != null) return resourceMatch

        // Nivel 2: Búsqueda semántica por Texto o ContentDescription exacto
        val directMatch = actionHelper.findNodeRecursively(root) { node ->
            if (isDot) {
                node.text?.toString() == "." ||
                        node.text?.toString() == "," ||
                        node.contentDescription?.toString() == "." ||
                        node.contentDescription?.toString() == "," ||
                        node.contentDescription?.contains("punto", ignoreCase = true) == true ||
                        node.contentDescription?.contains("dot", ignoreCase = true) == true
            } else {
                node.text?.toString() == digit || node.contentDescription?.toString() == digit
            }
        }
        if (directMatch != null) return directMatch

        // Nivel 3: Mapeo de Matriz Topológica aislando exclusivamente los 12 botones del teclado
        val keypadLeaves = mutableListOf<AccessibilityNodeInfo>()
        collectKeypadLeaves(root, keypadLeaves)

        val digitMap = mapGridToDigits(keypadLeaves)
        return digitMap[if (isDot) "." else digit]
    }

    private fun collectKeypadLeaves(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        val resId = node.viewIdResourceName
        if (resId != null && resId.contains("key-")) {
            list.add(node)
            return
        }

        // Si no tiene prefijo "key-", recolectar interactivos en la mitad inferior de la pantalla
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.top > 800) {
            if (node.childCount == 0 && (node.isClickable || node.parent?.isClickable == true)) {
                list.add(node)
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectKeypadLeaves(child, list)
        }
    }

    private fun mapGridToDigits(nodes: List<AccessibilityNodeInfo>): Map<String, AccessibilityNodeInfo> {
        if (nodes.isEmpty()) return emptyMap()

        // Ordenar nodos por fila vertical (top) y columna horizontal (left)
        val sortedNodes = nodes.sortedWith(compareBy({
            val rect = Rect()
            it.getBoundsInScreen(rect)
            rect.top
        }, {
            val rect = Rect()
            it.getBoundsInScreen(rect)
            rect.left
        }))

        // Distribución matricial estándar 3x4 del teclado numérico telefónico:
        // Fila 0: 1, 2, 3
        // Fila 1: 4, 5, 6
        // Fila 2: 7, 8, 9
        // Fila 3: ., 0, backspace
        val mapping = mutableMapOf<String, AccessibilityNodeInfo>()
        val keypadLayout = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "backspace")
        keypadLayout.forEachIndexed { index, label ->
            if (index < sortedNodes.size) {
                mapping[label] = sortedNodes[index]
            }
        }
        return mapping
    }

    private fun clickKeypadByCalculatedCoordinates(root: AccessibilityNodeInfo, digit: String): Boolean {
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        if (rootBounds.isEmpty) return false

        // Zona del teclado numérico inferior (aproximadamente entre el 65% y el 95% de la pantalla)
        val keypadTop = rootBounds.top + (rootBounds.height() * 0.65f)
        val keypadBottom = rootBounds.bottom - (rootBounds.height() * 0.05f)
        val keypadHeight = keypadBottom - keypadTop
        val keypadWidth = rootBounds.width().toFloat()

        val rowHeight = keypadHeight / 4f
        val colWidth = keypadWidth / 3f

        val (row, col) = when (digit) {
            "1" -> 0 to 0
            "2" -> 0 to 1
            "3" -> 0 to 2
            "4" -> 1 to 0
            "5" -> 1 to 1
            "6" -> 1 to 2
            "7" -> 2 to 0
            "8" -> 2 to 1
            "9" -> 2 to 2
            ".", "," -> 3 to 0 // Columna izquierda, fila inferior (el botón de punto)
            "0" -> 3 to 1      // Columna central, fila inferior (el botón cero)
            else -> return false
        }

        val targetX = col * colWidth + (colWidth / 2f)
        val targetY = keypadTop + (row * rowHeight) + (rowHeight / 2f)

        return actionHelper.dispatchGestureClick(targetX, targetY)
    }
}
