package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * 无障碍服务 - 纯诊断模式：遍历所有系统窗口，找邮付小助手悬浮窗里的金额
 * 不做任何业务操作，只打印日志
 */
class AlipayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "无障碍诊断"
        private var lastLogHash = 0L
        private var lastLogTime = 0L
        private const val DEDUP_INTERVAL = 2000L
        private val keywords = listOf("邮付", "收款", "到账", "¥", "￥", "元")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            // 遍历所有窗口，找悬浮窗
            val windows = windows ?: return
            if (windows.isEmpty()) return

            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
            val allTexts = mutableListOf<String>()

            for (window in windows) {
                val rootNode = window.root
                if (rootNode == null) continue
                traverseNode(rootNode, allTexts, 0)
                val winPackage = rootNode.packageName?.toString() ?: "unknown"
                LogManager.addLog("窗口诊断", "窗口#${window.id} 包名=$winPackage 类型=${window.type}")
            }

            // 去重：相同内容2秒内不重复打
            val hash = allTexts.joinToString("|").hashCode().toLong()
            if (hash == lastLogHash && System.currentTimeMillis() - lastLogTime < DEDUP_INTERVAL) {
                return
            }
            lastLogHash = hash
            lastLogTime = System.currentTimeMillis()

            LogManager.addLog("=== 窗口诊断 $timestamp ===", "共${windows.size}个窗口")
            // 打印所有包含关键词的候选文本
            allTexts.forEach { t ->
                if (keywords.any { t.contains(it) }) {
                    LogManager.addLog("悬浮窗候选文本", t)
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍诊断异常", e.message ?: "未知错误")
        }
    }

    /**
     * 递归遍历节点收集所有文本
     */
    private fun traverseNode(node: AccessibilityNodeInfo?, texts: MutableList<String>, depth: Int) {
        if (node == null || depth > 15) return
        try {
            if (!node.text.isNullOrEmpty()) texts.add(node.text.toString())
            if (!node.contentDescription.isNullOrEmpty()) texts.add(node.contentDescription.toString())
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), texts, depth + 1)
            }
        } catch (e: Exception) {
            // 忽略节点读取异常
        }
    }

    override fun onInterrupt() {}
}