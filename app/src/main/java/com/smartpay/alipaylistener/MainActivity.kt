package com.smartpay.alipaylistener

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.Window
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var adapter: PaymentAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvClock: TextView
    private lateinit var panelData: View
    private lateinit var panelDevice: View
    private lateinit var panelSettings: View
    private lateinit var bottomBar: View
    private lateinit var topActions: View
    private lateinit var etPairCode: EditText
    private lateinit var tvLog: TextView
    private val prefsName = "alipay_listener_prefs"
    private val paymentsKey = "recent_payments"
    private val handler = Handler(Looper.getMainLooper())
    private val recentPayments = mutableListOf<Payment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        bindViews()
        loadPayments()
        setupActions()
        showPanel(0)
        updateStatus()
        startClock()
        LogManager.setLogCallback { log -> runOnUiThread { tvLog.append(log + "\n") } }
        MqttClientManager.setPaymentCallback { amount, channel -> runOnUiThread { addPayment(amount, channel) } }
        MqttClientManager.setStatusCallback { _, _ -> runOnUiThread { updateStatus() } }
    }

    private fun bindViews() {
        listView = findViewById(R.id.lv_payments)
        adapter = PaymentAdapter(this, recentPayments)
        listView.adapter = adapter
        tvStatus = findViewById(R.id.tv_status_top)
        tvClock = findViewById(R.id.tv_clock)
        panelData = findViewById(R.id.panel_data)
        panelDevice = findViewById(R.id.panel_device)
        panelSettings = findViewById(R.id.panel_settings)
        bottomBar = findViewById(R.id.bottom_bar)
        topActions = findViewById(R.id.top_actions)
        etPairCode = findViewById(R.id.et_pair_code)
        tvLog = findViewById(R.id.tv_log)
        etPairCode.setText(getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("pair_code", ""))
        findViewById<TextView>(R.id.tab_data).setOnClickListener { showPanel(0) }
        findViewById<TextView>(R.id.tab_device).setOnClickListener { showPanel(1) }
        findViewById<TextView>(R.id.tab_settings).setOnClickListener { showPanel(2) }
    }

    private fun showPanel(index: Int) {
        panelData.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelDevice.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun setupActions() {
        findViewById<ImageButton>(R.id.btn_fullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<ImageButton>(R.id.btn_orientation).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val code = etPairCode.text.toString().trim()
            if (code.length != 6) { Toast.makeText(this, "请输入6位配对码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("pair_code", code).apply()
            val i = Intent(this, KeepAliveService::class.java).putExtra("pair_code", code)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "监听服务已启动", Toast.LENGTH_SHORT).show()
            updateStatus()
        }
        findViewById<Button>(R.id.btn_open_notification_access).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    private fun addPayment(amount: String, channel: String) {
        if (amount.trim().isEmpty()) return
        recentPayments.add(0, Payment(amount.trim(), if (channel.isEmpty()) "收款" else channel, System.currentTimeMillis()))
        while (recentPayments.size > 10) recentPayments.removeAt(recentPayments.lastIndex)
        savePayments()
        adapter.notifyDataSetChanged()
    }

    private fun savePayments() {
        val value = recentPayments.joinToString(";") { it.time.toString() + "|" + it.amount + "|" + it.channel }
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(paymentsKey, value).apply()
    }

    private fun loadPayments() {
        val value = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(paymentsKey, "") ?: return
        if (value.isEmpty()) return
        value.split(";").forEach {
            val p = it.split("|")
            if (p.size == 3) recentPayments.add(Payment(p[1], p[2], p[0].toLongOrNull() ?: System.currentTimeMillis()))
        }
        while (recentPayments.size > 10) recentPayments.removeAt(recentPayments.lastIndex)
    }

    private fun updateStatus() {
        val paired = MqttClientManager.isConnected() && MqttClientManager.isPaired()
        tvStatus.text = if (paired) "已连接" else "未连接"
        tvStatus.setTextColor(if (paired) 0xFF62D88F.toInt() else 0xFFF2B84B.toInt())
        findViewById<TextView>(R.id.tv_device_status).text = if (paired) "MQTT：已连接并已配对" else "MQTT：等待连接或配对"
        findViewById<TextView>(R.id.tv_notification_status).text = if (isNotificationServiceEnabled()) "通知监听：已开启" else "通知监听：未开启"
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() { tvClock.text = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date()); handler.postDelayed(this, 1000) }
        })
    }

    private fun toggleFullscreen() {
        val decor = window.decorView
        val flags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val full = (decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
        decor.systemUiVisibility = if (full) View.SYSTEM_UI_FLAG_LAYOUT_STABLE else flags
        bottomBar.visibility = if (full) View.VISIBLE else View.GONE
        // 全屏时仍保留右上角操作区，让“眼睛”按钮可再次点击退出全屏
        topActions.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE &&
            (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
            toggleFullscreen()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    data class Payment(val amount: String, val channel: String, val time: Long)

    private class PaymentAdapter(private val context: Context, private val items: List<Payment>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.item_payment, parent, false)
            val item = items[position]
            v.findViewById<TextView>(R.id.tv_payment_amount).text = "¥" + item.amount
            v.findViewById<TextView>(R.id.tv_payment_channel).text = item.channel
            v.findViewById<TextView>(R.id.tv_payment_time).text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.time))
            return v
        }
    }
}
