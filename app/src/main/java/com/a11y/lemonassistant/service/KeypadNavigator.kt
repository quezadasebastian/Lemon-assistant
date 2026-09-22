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
            val keyNode = findKeypadDigitNode(rootNode, char.toString())
            if (keyNode != null) {
                actionHelper.performSmartClick(keyNode)
                keyNode.recycle()
                delay(150) // Inter-digit debounce para evitar omisiones de React Native
            } else {
                return false
            }
        }
        return true
    }

    fun findKeypadDigitNode(root: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        // Nivel 1: Búsqueda semántica directa por Texto o ContentDescription exacto
        val directMatch = actionHelper.findNodeRecursively(root) { node ->
            node.text?.toString() == digit || node.contentDescription?.toString() == digit
        }
        if (directMatch != null) return directMatch

        // Nivel 2: Mapeo de Matriz Topológica de Nodos Interactivos (Fallback sin coordenadas)
        val interactiveLeaves = mutableListOf<AccessibilityNodeInfo>()
        collectInteractiveLeaves(root, interactiveLeaves)

        val digitMap = mapGridToDigits(interactiveLeaves)
        return digitMap[digit]
    }

    private fun collectInteractiveLeaves(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.childCount == 0 && (node.isClickable || node.parent?.isClickable == true)) {
            list.add(node)
        } else {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectInteractiveLeaves(child, list)
            }
        }
    }

    private fun mapGridToDigits(nodes: List<AccessibilityNodeInfo>): Map<String, AccessibilityNodeInfo> {
        if (nodes.size < 10) return emptyMap()

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

        val mapping = mutableMapOf<String, AccessibilityNodeInfo>()
        val keypadLayout = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0")
        keypadLayout.forEachIndexed { index, label ->
            if (index < sortedNodes.size) {
                mapping[label] = sortedNodes[index]
            }
        }
        return mapping
    }
}
