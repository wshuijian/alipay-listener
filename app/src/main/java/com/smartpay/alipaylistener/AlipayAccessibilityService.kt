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
            // 连续dump3次，确保微信页面加载完
            for (delay in longArrayOf(1000, 2000, 3000)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dumpCurrentWindow()
                }, delay)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !dumpMode) return
    }

    private fun dumpCurrentWindow() {
        if (!dumpMode) return
        try {
            val root = rootInActiveWindow ?: run {
                LogManager.addLog("无障碍dump", "root window 为 null")
                return
            }
            val pkg = root.packageName?.toString() ?: ""
            LogManager.addLog("无障碍dump", "===== dump页面 (包名:$pkg) =====")
            val sb = StringBuilder()
            traverseNode(root, sb, 0)
            LogManager.addLog("无障碍dump", "页面所有文本:\n$sb")
            LogManager.addLog("无障碍dump", "===== dump结束 =====")
        } catch (e: Exception) {
            LogManager.addLog("无障碍dump失败", e.message ?: "未知错误")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 20) return
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
