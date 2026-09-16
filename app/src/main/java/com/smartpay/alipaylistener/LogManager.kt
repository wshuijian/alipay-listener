package com.smartpay.alipaylistener

/**
 * 全局日志管理器，把所有日志收集起来显示到APP界面
 */
object LogManager {

    private val logs = mutableListOf<String>()
    private var logCallback: ((String) -> Unit)? = null
    private const val MAX_LOGS = 200

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
        // 把已有的日志都发过去
        logs.forEach { callback(it) }
    }

    fun addLog(tag: String, message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val log = "[$time] $tag: $message"

        synchronized(logs) {
            logs.add(log)
            if (logs.size > MAX_LOGS) {
                logs.removeAt(0)
            }
        }

        logCallback?.invoke(log)
        println(log)
    }

    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
        }
    }

    fun getAllLogs(): List<String> {
        return synchronized(logs) { logs.toList() }
    }
}
