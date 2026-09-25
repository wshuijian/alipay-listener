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
            LogManager.addLog("手动dump", "===== 开始枚举所有窗口 =====")
            val windows = windows
            LogManager.addLog("手动dump", "当前共 ${windows.size} 个窗口")
            for (i in windows.indices) {
                val win = windows[i]
                val pkg = win.root?.packageName?.toString() ?: "未知"
                LogManager.addLog("手动dump", "窗口#$i: 包名=$pkg 是否活动=${win.isActive}")
                val sb = StringBuilder()
                traverseNode(win.root, sb, 1)
                if (sb.isNotEmpty()) {
                    LogManager.addLog("手动dump", "窗口#$i 内容:\n$sb")
                }
            }
            val root = rootInActiveWindow
            if (root != null) {
                LogManager.addLog("手动dump", "活动主窗口包名: ${root.packageName}")
                val sb = StringBuilder()
                traverseNode(root, sb, 1)
                LogManager.addLog("手动dump", "活动主窗口内容:\n$sb")
            } else {
                LogManager.addLog("手动dump", "活动主窗口root为null")
            }
            LogManager.addLog("手动dump", "===== 枚举结束 =====")
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
