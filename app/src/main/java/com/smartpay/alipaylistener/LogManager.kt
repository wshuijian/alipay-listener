package com.smartpay.alipaylistener

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 全局日志管理器：界面显示最近日志，同时保存可导出的日志文件。 */
object LogManager {
    private val logs = mutableListOf<String>()
    @Volatile private var logCallback: ((String) -> Unit)? = null
    private const val MAX_LOGS = 2000
    private const val MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L
    private val fileLock = Any()
    @Volatile private var logFile: File? = null
    @Volatile private var backupLogFile: File? = null
    private val logWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SmartPay-LogWriter").apply { isDaemon = true }
    }

    fun init(context: Context) {
        synchronized(fileLock) {
            if (logFile != null) return
            val dir = File(context.applicationContext.filesDir, "diagnostic_logs")
            if (!dir.exists() && !dir.mkdirs()) return
            logFile = File(dir, "smartpay.log")
            backupLogFile = File(dir, "smartpay.previous.log")
        }
    }

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
        synchronized(logs) { logs.toList() }.forEach { callback(it) }
    }

    fun addLog(tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$time] $tag: $message"
        synchronized(logs) {
            logs.add(entry)
            while (logs.size > MAX_LOGS) logs.removeAt(0)
        }
        // 异步落盘，避免日志 I/O 阻塞收款、MQTT 回调。
        try { logWriter.execute { appendToFile(entry) } } catch (_: Exception) {}
        try { logCallback?.invoke(entry) } catch (_: Exception) {}
        println(entry)
    }

    private fun appendToFile(entry: String) {
        synchronized(fileLock) {
            val file = logFile ?: return
            val backup = backupLogFile ?: return
            try {
                val bytes = (entry + "\n").toByteArray(Charsets.UTF_8)
                if (file.exists() && file.length() + bytes.size > MAX_LOG_FILE_BYTES) {
                    if (backup.exists()) backup.delete()
                    if (!file.renameTo(backup)) file.delete()
                }
                FileOutputStream(file, true).buffered().use { it.write(bytes) }
            } catch (_: Exception) {}
        }
    }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
        try {
            logWriter.execute {
                synchronized(fileLock) {
                    logFile?.delete()
                    backupLogFile?.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun getAllLogs(): List<String> = synchronized(logs) { logs.toList() }

    /** 导出当前日志与上一段轮转日志，最多约 4 MiB。 */
    fun exportFullLogs(context: Context): File {
        val future: Future<File> = logWriter.submit<File> {
            synchronized(fileLock) {
                val exportFile = File(context.cacheDir, "smartpay-logs-export.txt")
                BufferedOutputStream(FileOutputStream(exportFile, false)).use { output ->
                    listOfNotNull(backupLogFile, logFile).forEach { source ->
                        if (source.exists()) source.inputStream().buffered().use { it.copyTo(output) }
                    }
                }
                exportFile
            }
        }
        return future.get()
    }
}
