package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * 无障碍服务 - 纯诊断模式
 */
class AlipayAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 不做任何狂刷日志的操作，保持稳定
    }
    override fun onInterrupt() {}
}