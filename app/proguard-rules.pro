# ============================================================
# 智慧收款 - Release混淆保留规则
# 原则：只保留系统/框架必须按类名实例化的组件，其余尽量混淆
# ============================================================

# --- 保留行号信息，便于线上崩溃堆栈定位 ---
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Android四大组件（Manifest已声明，显式再保留一层保险）---
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# --- 通知监听/无障碍服务子类：系统通过BIND_*权限绑定回调，类名不可混淆 ---
-keep public class com.smartpay.alipaylistener.AlipayNotificationListener
-keep public class com.smartpay.alipaylistener.AlipayAccessibilityService
-keep public class com.smartpay.alipaylistener.JinNongProbeAccessibilityService
-keep public class com.smartpay.alipaylistener.PaymentAccessibilityService
-keep public class com.smartpay.alipaylistener.KeepAliveService
-keep public class com.smartpay.alipaylistener.SpeakerService

# --- 保留上述Service的override回调方法签名 ---
# （onNotificationPosted / onAccessibilityEvent 等是框架回调，R8默认已保留，此处显式声明）
-keepclassmembers public class * extends android.service.notification.NotificationListenerService {
    public void onNotificationPosted(android.service.notification.StatusBarNotification);
    public void onNotificationRemoved(android.service.notification.StatusBarNotification);
}
-keepclassmembers public class * extends android.accessibilityservice.AccessibilityService {
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
}

# --- Paho MQTT：保留回调接口实现类（我们在代码里匿名实现MqttCallbackExtended）---
-keep class org.eclipse.paho.client.mqttv3.** { *; }

# --- 我们的LicenseManager/业务类：无反射调用，不做整体保留，允许混淆 ---
# （Payment data class 手动字符串序列化，R8混淆字段名不影响读写）

# --- 移除日志（Release版去掉System.out/Log打印）---
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
