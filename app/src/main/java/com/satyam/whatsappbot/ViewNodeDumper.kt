package com.satyam.whatsappbot

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object ViewNodeDumper {
    fun dump(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return listOf("NODE_DUMP root=<null>")
        val output = mutableListOf<String>()
        visit(root, 0, output)
        return output
    }

    private fun visit(node: AccessibilityNodeInfo, depth: Int, output: MutableList<String>) {
        val indent = "  ".repeat(depth.coerceAtMost(12))
        val id = node.viewIdResourceName ?: "<none>"
        val className = node.className ?: "<none>"
        val text = when {
            node.isEditable -> DebugLogStore.safeText(node.text)
            isToolbarLabel(node) -> DebugLogStore.safeText(node.text)
            else -> DebugLogStore.maskedText(node.text)
        }
        val description = DebugLogStore.maskedText(node.contentDescription)
        output += "${indent}id=$id class=$className text=$text desc=$description"
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                visit(child, depth + 1, output)
                child.recycle()
            }
        }
    }

    private fun isToolbarLabel(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        if (text.isEmpty() || text.length > 80) return false
        if (node.className?.toString() != "android.widget.TextView") return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.top <= 240
    }
}
