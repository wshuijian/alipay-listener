package com.smartpay.alipaylistener

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class AlipayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AlipayListener"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val ALIPAY_PAY_CHANNEL = "alipay_default"

        // 绗笁闃舵锛氱函璇婃柇妯″紡锛屽彧璁板綍锛屼笉瑙ｆ瀽锛屼笉鍙戦€丮QTT
        private const val DIAGNOSTICS_ONLY = false

        // 闃查噸澶嶏細璁板綍宸茬粡澶勭悊杩囩殑閫氱煡key
        private val processedKeys = mutableSetOf<String>()

        // 璁板綍宸茬粡鍑虹幇杩囩殑閫氱煡key锛岀敤浜庡垽鏂槸POST杩樻槸UPDATE
        private val seenNotificationKeys = mutableSetOf<String>()

        // 閲戦姝ｅ垯锛氫粠"浣犲凡鎴愬姛鏀舵0.01鍏?閲屾彁鍙?.01
        private val ALIPAY_AMOUNT_PATTERN = Pattern.compile("浣犲凡鎴愬姛鏀舵([\\d]+\\.?[\\d]*)鍏?)
        // 寰俊閲戦姝ｅ垯锛氫粠"寰俊鏀粯鏀舵0.01鍏?閲屾彁鍙?.01
        private val WECHAT_AMOUNT_PATTERN = Pattern.compile("寰俊鏀粯鏀舵([\\d]+\\.?[\\d]*)鍏?)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogManager.addLog("绯荤粺", "閫氱煡鐩戝惉鏈嶅姟宸茶繛鎺?)
        Log.i(TAG, "閫氱煡鐩戝惉鏈嶅姟宸茶繛鎺?)
        dumpActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogManager.addLog("绯荤粺", "閫氱煡鐩戝惉鏈嶅姟宸叉柇寮€")
        Log.i(TAG, "閫氱煡鐩戝惉鏈嶅姟宸叉柇寮€")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) {
            LogManager.addLog("閫氱煡POST", "sbn == null")
            Log.w(TAG, "onNotificationPosted: sbn == null")
            return
        }

        logNotificationPosted(sbn)

        // 璋冭瘯闃舵鍒版涓烘锛屼笉杩涘叆閲戦瑙ｆ瀽/MQTT 娴佺▼锛岄伩鍏嶅涓棶棰樻贩鍦ㄤ竴璧枫€?        if (!DIAGNOSTICS_ONLY) {
            processNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) {
            LogManager.addLog("閫氱煡REMOVE", "sbn == null")
            Log.w(TAG, "onNotificationRemoved: sbn == null")
            return
        }
        logNotificationRemoved(sbn, null)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        if (sbn == null) {
            LogManager.addLog("閫氱煡REMOVE", "sbn == null, reason=$reason")
            Log.w(TAG, "onNotificationRemoved: sbn == null, reason=$reason")
            return
        }
        logNotificationRemoved(sbn, reason)
    }

    private fun logNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification?.extras

        // 鍒ゆ柇鏄柊閫氱煡POST杩樻槸閫氱煡鏇存柊UPDATE
        val event: String
        if (seenNotificationKeys.contains(sbn.key)) {
            event = "UPDATE"
        } else {
            event = "POST"
            seenNotificationKeys.add(sbn.key)
        }

        val summary = buildNotificationSummary(sbn, event)

        LogManager.addLog("閫氱煡$event", summary)
        Log.i(TAG, "onNotificationPosted ($event)\n$summary")

        // 鏀粯瀹濈殑鎵€鏈夐€氱煡閮芥墦鍗板畬鏁磂xtras锛屼笉绠℃槸POST杩樻槸UPDATE
        if (sbn.packageName == ALIPAY_PACKAGE) {
            LogManager.addLog("鏀粯瀹?{event}", "鏀跺埌鏀粯瀹?{event}閫氱煡锛岃緭鍑哄畬鏁?extras")
            Log.i(TAG, "Alipay ${event} notification, dumping extras")
            dumpExtras(extras, "鏀粯瀹?{event} extras")
        }
    }

    private fun logNotificationRemoved(sbn: StatusBarNotification, reason: Int?) {
        val notification = sbn.notification
        val extras = notification?.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val summary = buildString {
            appendLine("packageName=${sbn.packageName}")
            appendLine("key=${sbn.key}")
            appendLine("id=${sbn.id}")
            appendLine("tag=${sbn.tag}")
            appendLine("reason=${reason?.toString() ?: "N/A(鍗曞弬鏁板洖璋冩湭鎻愪緵 reason)"}")
            appendLine("title=$title")
            appendLine("text=$text")
            appendLine("bigText=$bigText")
            appendLine("postTime=${sbn.postTime} (${formatTime(sbn.postTime)})")
        }.trimEnd()

        LogManager.addLog("閫氱煡REMOVE", summary)
        Log.i(TAG, "onNotificationRemoved\n$summary")
    }

    private fun dumpActiveNotifications() {
        try {
            val active = getActiveNotifications()
            val count = active?.size ?: 0
            LogManager.addLog("绯荤粺", "onListenerConnected: activeNotifications 鏁伴噺=$count")
            Log.i(TAG, "onListenerConnected: activeNotifications count=$count")

            if (active == null || active.isEmpty()) {
                LogManager.addLog("绯荤粺", "褰撳墠娌℃湁 active notifications")
                return
            }

            active.forEach { sbn ->
                val summary = buildNotificationSummary(sbn, "ACTIVE")

                LogManager.addLog("閫氱煡ACTIVE", summary)
                Log.i(TAG, "active notification\n$summary")

                if (sbn.packageName == ALIPAY_PACKAGE) {
                    LogManager.addLog("鏀粯瀹滱CTIVE", "active 涓瓨鍦ㄦ敮浠樺疂閫氱煡锛岃緭鍑哄畬鏁?extras")
                    Log.i(TAG, "Alipay active notification, dumping extras")
                    dumpExtras(sbn.notification?.extras, "鏀粯瀹滱CTIVE extras")
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("绯荤粺", "璇诲彇 activeNotifications 寮傚父: ${e.message}")
            Log.e(TAG, "getActiveNotifications failed", e)
        }
    }

    private fun buildNotificationSummary(sbn: StatusBarNotification, source: String): String {
        val notification = sbn.notification
        val extras = notification?.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        return buildString {
            appendLine("source=$source")
            appendLine("packageName=${sbn.packageName}")
            appendLine("key=${sbn.key}")
            appendLine("id=${sbn.id}")
            appendLine("tag=${sbn.tag}")
            appendLine("postTime=${sbn.postTime} (${formatTime(sbn.postTime)})")
            appendLine("groupKey=${groupKeyOf(sbn)}")
            appendLine("isGroup=${isGroupOf(sbn)}")
            appendLine("flags=${flagsOf(notification)}")
            appendLine("channelId=${channelIdOf(notification)}")
            appendLine("title=$title")
            appendLine("text=$text")
            appendLine("bigText=$bigText")
            appendLine("subText=$subText")
            appendLine("tickerText=${notification?.tickerText}")
        }.trimEnd()
    }

    private fun dumpExtras(extras: Bundle?, label: String) {
        if (extras == null) {
            LogManager.addLog(label, "extras == null")
            Log.i(TAG, "$label: extras == null")
            return
        }

        if (extras.keySet().isEmpty()) {
            LogManager.addLog(label, "extras 涓虹┖")
            Log.i(TAG, "$label: extras is empty")
            return
        }

        val keys = extras.keySet().sorted()
        LogManager.addLog(label, "extras 鍏?${keys.size} 涓瓧娈?)
        keys.forEach { key ->
            val line = "$key=${describeExtrasValue(extras, key)}"
            LogManager.addLog(label, line)
            Log.i(TAG, "$label: $line")
        }
    }

    private fun describeExtrasValue(bundle: Bundle, key: String): String {
        return try {
            when (val value = bundle.get(key)) {
                null -> "null"
                is Bundle -> "Bundle{${value.keySet().joinToString(", ")}}"
                is CharSequence -> value.toString()
                is Array<*> -> value.contentToString()
                is IntArray -> value.contentToString()
                is LongArray -> value.contentToString()
                is ShortArray -> value.contentToString()
                is ByteArray -> "ByteArray(size=${value.size})"
                is FloatArray -> value.contentToString()
                is DoubleArray -> value.contentToString()
                is BooleanArray -> value.contentToString()
                is CharArray -> value.contentToString()
                else -> "${value.javaClass.simpleName}: $value"
            }
        } catch (e: Exception) {
            "<鏃犳硶璇诲彇: ${e.message}>"
        }
    }

    private fun groupKeyOf(sbn: StatusBarNotification): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            sbn.groupKey ?: "null"
        } else {
            "N/A(<20)"
        }
    }

    private fun isGroupOf(sbn: StatusBarNotification): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            sbn.isGroup.toString()
        } else {
            "N/A(<20)"
        }
    }

    private fun flagsOf(notification: Notification?): String {
        val flags = notification?.flags ?: return "null"
        return "$flags (0x${Integer.toHexString(flags)})"
    }

    private fun channelIdOf(notification: Notification?): String {
        if (notification == null) return "null"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId ?: "null"
        } else {
            "N/A(<26)"
        }
    }

    private fun formatTime(millis: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(millis))
        } catch (e: Exception) {
            millis.toString()
        }
    }

    /**
     * 澶勭悊鏀舵閫氱煡
     * 鍚屾椂鏀寔鏀粯瀹濆拰寰俊
     */
    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE, "")?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT, "")?.toString() ?: ""

            // 闃查噸澶嶏細鐢ㄩ€氱煡key+postTime鍘婚噸
            val eventKey = "${sbn.key}_${sbn.postTime}"
            if (processedKeys.contains(eventKey)) {
                LogManager.addLog("闃查噸澶?, "鍚屼竴鏉￠€氱煡锛屽拷鐣?)
                return
            }
            processedKeys.add(eventKey)
            if (processedKeys.size > 100) processedKeys.clear()

            when (packageName) {
                // 澶勭悊鏀粯瀹?                ALIPAY_PACKAGE -> {
                    val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        notification.channelId ?: ""
                    } else {
                        ""
                    }
                    // 鍙鐞嗘敹娆鹃€氱煡閫氶亾锛屾帓闄?鏀堕挶鎻愰啋鍔╂墜"閭ｄ釜voice_helper閫氶亾
                    if (channelId != ALIPAY_PAY_CHANNEL) {
                        return
                    }

                    LogManager.addLog("鏀粯瀹濇敹娆鹃€氱煡", "title=$title | text=$text")

                    // 浠巘itle涓彁鍙栭噾棰濓細"浣犲凡鎴愬姛鏀舵0.01鍏冿紙鑰侀【瀹㈡秷璐癸級" 鈫?0.01
                    val matcher = ALIPAY_AMOUNT_PATTERN.matcher(title)
                    if (!matcher.find()) {
                        LogManager.addLog("鏀粯瀹濊В鏋愬け璐?, "title涓病鎵惧埌閲戦")
                        return
                    }

                    val amount = matcher.group(1)
                    LogManager.addLog("鉁?鏀粯瀹濊В鏋愭垚鍔?, "閲戦:楼$amount")

                    // 鍙戦€佺粰PC绔?                    LogManager.addLog("MQTT", "姝ｅ湪鍙戦€佹敮浠樺疂閲戦${amount}鍒癙C绔?..")
                    MqttClientManager.sendPayment(amount = amount, rawText = "ALIPAY|$title")
                    LogManager.addLog("MQTT", "鍙戦€佸畬鎴?)
                }

                // 澶勭悊寰俊
                WECHAT_PACKAGE -> {
                    LogManager.addLog("寰俊鏀舵閫氱煡", "title=$title | text=$text")

                    // 浠巘ext涓彁鍙栭噾棰濓細"寰俊鏀粯鏀舵0.01鍏?鑰侀【瀹㈢50娆℃秷璐?" 鈫?0.01
                    val matcher = WECHAT_AMOUNT_PATTERN.matcher(text)
                    if (!matcher.find()) {
                        LogManager.addLog("寰俊瑙ｆ瀽澶辫触", "text涓病鎵惧埌閲戦")
                        return
                    }

                    val amount = matcher.group(1)
                    LogManager.addLog("鉁?寰俊瑙ｆ瀽鎴愬姛", "閲戦:楼$amount")

                    // 鍙戦€佺粰PC绔?                    LogManager.addLog("MQTT", "姝ｅ湪鍙戦€佸井淇￠噾棰?{amount}鍒癙C绔?..")
                    MqttClientManager.sendPayment(amount = amount, rawText = "WECHAT|$text")
                    LogManager.addLog("MQTT", "鍙戦€佸畬鎴?)
                }
            }

        } catch (e: Exception) {
            LogManager.addLog("鉂?寮傚父", e.message ?: "鏈煡閿欒")
            Log.e(TAG, "澶勭悊閫氱煡寮傚父", e)
        }
    }
}
