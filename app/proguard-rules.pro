# ============================================================
# R8 / ProGuard 规则
# 现状：JSON 全部走 org.json 手动解析，无 Gson/Moshi/反射序列化；
# Activity/Service/Receiver 跳转均为编译期 ::class.java 引用，无类名反射。
# ============================================================

# ---- 应用四大组件（Manifest 注册；R8 通常自动保留，这里显式兜底类与成员） ----
-keepclasseswithmembers class com.mh.starrailcheckin.** extends android.app.Activity { *; }
-keepclasseswithmembers class com.mh.starrailcheckin.** extends android.content.BroadcastReceiver { *; }
-keepclasseswithmembers class com.mh.starrailcheckin.** extends android.app.Service { *; }
-keepclasseswithmembers class com.mh.starrailcheckin.** extends android.accessibilityservice.AccessibilityService { *; }

# ---- WorkManager Worker：框架按类名反射实例化，保留类与双参构造函数 ----
-keep class com.mh.starrailcheckin.util.*Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- 第三方库可选缺失类告警抑制（库自带 consumer 规则，这里仅降噪） ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn com.yalantis.ucrop.**
-dontwarn com.google.zxing.**
