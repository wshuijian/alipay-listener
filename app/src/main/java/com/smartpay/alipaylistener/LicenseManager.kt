package com.smartpay.alipaylistener

import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 商业授权状态管理（第一阶段：本地状态存储 + 服务器验证接口预留）
 *
 * 设计原则：
 * - 授权最终真伪以后台服务器返回为准，APK内不内置任何签名私钥/密钥。
 * - 本类只负责：设备标识、本地已确认授权结果的存取、到期判断、UI展示文案。
 * - 真正的激活码校验走 [verifyWithServer]，当前服务器未接入时一律返回未配置，
 *   本地绝不伪造“已激活”状态。
 */
object LicenseManager {

    private const val PREFS = "alipay_listener_prefs"
    private const val KEY_TYPE = "license_type"
    private const val KEY_EXPIRE_AT = "license_expire_at"
    private const val KEY_CODE = "license_code"
    private const val KEY_BOUND_DEVICE = "license_bound_device"

    /** 授权档位 */
    enum class LicenseType(val displayName: String, val durationDays: Long) {
        UNACTIVATED("未激活", 0),
        QUARTERLY("季度版", 90L),
        YEARLY("年度版", 365L),
        TRIENNIAL("三年版", 365L * 3),
        PERMANENT("永久版", Long.MAX_VALUE);
    }

    /** 激活结果 */
    sealed class ActivateResult {
        data class Success(val type: LicenseType, val expireAt: Long) : ActivateResult()
        data class Failure(val message: String) : ActivateResult()
    }

    /** 服务器校验原始结果（预留接口形态） */
    private data class ServerVerifyResult(
        val ok: Boolean,
        val type: LicenseType = LicenseType.UNACTIVATED,
        val expireAt: Long = 0L,
        val message: String = ""
    )

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 设备唯一标识（ANDROID_ID，无需额外权限） */
    fun getDeviceId(): String {
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNKNOWN_DEVICE"
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取当前本地已确认的授权信息 */
    fun getLicense(): LicenseType {
        val name = prefs().getString(KEY_TYPE, LicenseType.UNACTIVATED.name)
        return runCatching { LicenseType.valueOf(name ?: LicenseType.UNACTIVATED.name) }
            .getOrDefault(LicenseType.UNACTIVATED)
    }

    fun getExpireAt(): Long = prefs().getLong(KEY_EXPIRE_AT, 0L)

    fun getBoundDevice(): String = prefs().getString(KEY_BOUND_DEVICE, "") ?: ""

    /** 当前授权是否有效（已激活且未过期，永久版视为长期有效） */
    fun isActive(): Boolean {
        val type = getLicense()
        if (type == LicenseType.UNACTIVATED) return false
        if (type == LicenseType.PERMANENT) return true
        // 设备绑定校验：换机后本地授权失效，需重新激活
        if (getBoundDevice() != getDeviceId()) return false
        return System.currentTimeMillis() < getExpireAt()
    }

    /** 剩余天数；永久版返回 -1；未激活返回 0 */
    fun daysLeft(): Long {
        val type = getLicense()
        if (type == LicenseType.UNACTIVATED) return 0L
        if (type == LicenseType.PERMANENT) return -1L
        val remain = getExpireAt() - System.currentTimeMillis()
        return if (remain <= 0) 0L else (remain / (24L * 60L * 60L * 1000L))
    }

    /** 状态文案（直接给用户看，不出现技术术语） */
    fun statusText(): String {
        val type = getLicense()
        if (type == LicenseType.UNACTIVATED) return "状态：未激活（功能可试用，激活后解锁正式版）"
        if (getBoundDevice() != getDeviceId()) return "状态：授权与本机设备不匹配，请重新激活"
        return when (type) {
            LicenseType.PERMANENT -> "状态：永久版授权（本机已绑定）"
            else -> {
                if (System.currentTimeMillis() >= getExpireAt()) {
                    "状态：${type.displayName} 已过期，请续费激活"
                } else {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(getExpireAt()))
                    "状态：${type.displayName} 有效至 $date（剩余 ${daysLeft()} 天）"
                }
            }
        }
    }

    /**
     * 激活入口：校验激活码。
     * 当前阶段：后台授权服务器尚未接入，直接返回未配置，绝不本地伪造授权。
     * 服务器接入后，在此方法内发起HTTPS请求，把服务器返回的type/expireAt写入本地。
     */
    fun activate(code: String): ActivateResult {
        val trimmed = code.trim().uppercase(Locale.ROOT)
        if (trimmed.length < 8) {
            return ActivateResult.Failure("激活码格式不正确，请核对后重试")
        }
        // —— 服务器验证预留接口（第一阶段不启用）——
        val serverResult = verifyWithServer(trimmed, getDeviceId())
        return if (serverResult.ok) {
            prefs().edit()
                .putString(KEY_TYPE, serverResult.type.name)
                .putLong(KEY_EXPIRE_AT, serverResult.expireAt)
                .putString(KEY_CODE, trimmed)
                .putString(KEY_BOUND_DEVICE, getDeviceId())
                .apply()
            ActivateResult.Success(serverResult.type, serverResult.expireAt)
        } else {
            ActivateResult.Failure(serverResult.message.ifEmpty { "激活失败，请稍后重试或联系客服" })
        }
    }

    /**
     * 服务器校验接口（预留，暂未接入）。
     * 未来真实实现示例：
     *   POST https://授权服务器/api/activate  body={code, device_id}
     *   响应示例：{ "ok": true, "type": "yearly", "expire_at": 1793500000000, "signature": "..." }
     * 注意：服务器私钥只在服务端，APK只做HTTPS请求和结果落地，不内置任何验签密钥。
     */
    private fun verifyWithServer(code: String, deviceId: String): ServerVerifyResult {
        // TODO: 服务器上线后替换为真实HTTP请求
        return ServerVerifyResult(
            ok = false,
            message = "授权服务器尚未接入，暂时无法在线激活。当前版本可先试用全部收款功能。"
        )
    }
}
