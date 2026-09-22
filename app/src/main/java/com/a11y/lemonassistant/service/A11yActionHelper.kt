package com.a11y.lemonassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

class A11yActionHelper(private val service: AccessibilityService) {

    fun performSmartClick(targetNode: AccessibilityNodeInfo?): Boolean {
        if (targetNode == null) return false

        // 1. Clic directo en el nodo objetivo si es interactivo
        if (targetNode.isClickable) {
            val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) return true
        }

        // 2. Ascender en el árbol en busca de un contenedor interactivo (React Native ViewGroup)
        var currentParent = targetNode.parent
        while (currentParent != null) {
            if (currentParent.isClickable) {
                val success = currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                currentParent.recycle()
                if (success) return true
                break
            }
            val nextParent = currentParent.parent
            currentParent.recycle()
            currentParent = nextParent
        }

        // 3. Fallback: Despacho de gesto físico sobre el baricentro geométrico (BoundsInScreen)
        return clickCentroid(targetNode)
    }

    fun clickCentroid(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        val path = Path().apply {
            moveTo(centerX, centerY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return service.dispatchGesture(gesture, null, null)
    }

    fun setTextSafely(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun findNodeRecursively(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeRecursively(child, predicate)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    fun findFirstByTextOrDesc(
        root: AccessibilityNodeInfo?,
        textQuery: String,
        ignoreCase: Boolean = true
    ): AccessibilityNodeInfo? {
        return findNodeRecursively(root) { node ->
            val nodeText = node.text?.toString()
            val nodeDesc = node.contentDescription?.toString()
            (nodeText != null && nodeText.contains(textQuery, ignoreCase = ignoreCase)) ||
                    (nodeDesc != null && nodeDesc.contains(textQuery, ignoreCase = ignoreCase))
        }
    }
}
