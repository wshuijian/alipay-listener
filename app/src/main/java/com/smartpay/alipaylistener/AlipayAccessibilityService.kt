package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AlipayAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        private var dumpMode = false
        fun startDumpMode() {
            dumpMode = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !dumpMode) return
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != "com.tencent.mm") return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dumpCurrentWindow()
                }, 500)
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍dump异常", e.message ?: "未知错误")
        }
    }

    private fun dumpCurrentWindow() {
        if (!dumpMode) return
        try {
            val root = rootInActiveWindow ?: run {
                LogManager.addLog("无障碍dump", "root window 为 null")
                return
            }
            LogManager.addLog("无障碍dump", "===== 开始dump当前微信页面 =====")
            val sb = StringBuilder()
            traverseNode(root, sb, 0)
            LogManager.addLog("无障碍dump", "页面所有文本:\n$sb")
            LogManager.addLog("无障碍dump", "===== dump结束 =====")
            dumpMode = false
        } catch (e: Exception) {
            LogManager.addLog("无障碍dump失败", e.message ?: "未知错误")
            dumpMode = false
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 15) return
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
