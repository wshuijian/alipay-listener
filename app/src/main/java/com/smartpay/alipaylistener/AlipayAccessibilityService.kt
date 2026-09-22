package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 无障碍服务 - 验证自带截屏能力，保存截图看能不能抓到悬浮窗金额
 */
class AlipayAccessibilityService : AccessibilityService() {
    private var lastTriggerTime = 0L
    private val screenshotDir by lazy {
        File(getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString() ?: return
            // 只要收到邮付小助手相关的事件，就触发截屏验证
            event.text.forEach { t ->
                val text = t.toString()
                if ((text.contains("邮付") || text.contains("收款到账通知")) && System.currentTimeMillis() - lastTriggerTime > 3000) {
                    lastTriggerTime = System.currentTimeMillis()
                    LogManager.addLog("无障碍截屏", "收到触发事件，开始截屏验证...")
                    takeScreenshot()
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍截屏异常", e.message ?: "未知错误")
        }
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = java.util.concurrent.Executor { it.run() }
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        hardwareBuffer.close()
                        val timestamp = SimpleDateFormat("HHmmss", Locale.CHINA).format(Date())
                        val file = File(screenshotDir, "youfu_$timestamp.png")
                        FileOutputStream(file).use { out ->
                            bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        LogManager.addLog("无障碍截屏", "截图已保存到: ${file.absolutePath}")
                    } catch (e: Exception) {
                        LogManager.addLog("无障碍截屏失败", e.message ?: "未知错误")
                    }
                }
                override fun onFailure(errorCode: Int) {
                    LogManager.addLog("无障碍截屏失败", "错误码: $errorCode")
                }
            }
            takeScreenshot(0, executor, callback)
        }
    }

    override fun onInterrupt() {}
}