package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

class AlipayAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: AlipayAccessibilityService? = null
        fun manualDumpNow() {
            instance?.dumpAllWindows()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    fun dumpAllWindows() {
        try {
            LogManager.addLog("窗口探测", "===== 开始枚举所有窗口 =====")
            val windows = windows
            LogManager.addLog("窗口探测", "总窗口数量: ${windows.size}")

            for (i in windows.indices) {
                val win = windows[i]
                val root = win.root
                val bounds = Rect()
                root?.getBoundsInScreen(bounds)

                LogManager.addLog("窗口探测", "窗口#$i:")
                LogManager.addLog("窗口探测", "  windowId: ${win.id}")
                LogManager.addLog("窗口探测", "  type: ${win.type}")
                LogManager.addLog("窗口探测", "  isActive: ${win.isActive}")
                LogManager.addLog("窗口探测", "  isFocused: ${win.isFocused}")
                LogManager.addLog("窗口探测", "  packageName: ${root?.packageName}")
                LogManager.addLog("窗口探测", "  bounds: $bounds")
                LogManager.addLog("窗口探测", "  rootChildCount: ${root?.childCount}")

                val sb = StringBuilder()
                traverseNode(root, sb, 1)
                if (sb.isNotEmpty()) {
                    LogManager.addLog("窗口探测", "  窗口内容:\n$sb")
                }
            }
            LogManager.addLog("窗口探测", "===== 窗口枚举结束 =====")
        } catch (e: Exception) {
            LogManager.addLog("窗口探测失败", e.message ?: "未知错误")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 30) return
        try {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val visible = node.isVisibleToUser

            if (text.isNotEmpty() || desc.isNotEmpty()) {
                sb.appendLine("  ".repeat(depth) + "[$cls] text='$text' desc='$desc' visible=$visible id=$viewId")
            }
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), sb, depth + 1)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
