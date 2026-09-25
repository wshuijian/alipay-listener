package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AlipayAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: AlipayAccessibilityService? = null
        fun manualDumpNow() {
            instance?.dumpCurrentWindow()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    fun dumpCurrentWindow() {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                LogManager.addLog("手动dump", "root窗口为null")
                return
            }
            val pkg = root.packageName?.toString() ?: ""
            LogManager.addLog("手动dump", "===== 开始读取当前屏幕 (包名:$pkg) =====")
            val sb = StringBuilder()
            traverseNode(root, sb, 0)
            LogManager.addLog("手动dump", "所有文字:\n$sb")
            LogManager.addLog("手动dump", "===== 读取结束 =====")
        } catch (e: Exception) {
            LogManager.addLog("手动dump失败", e.message ?: "未知错误")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 25) return
        try {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString() ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                sb.appendLine("  ".repeat(depth) + "[$cls] text='$text' desc='$desc'")
            }
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), sb, depth + 1)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
