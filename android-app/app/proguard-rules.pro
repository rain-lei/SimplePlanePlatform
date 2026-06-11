# 保留被 JNI 反射调用的类与方法。
# Rust 侧通过 JNI 按名查找 NativeBridge 的 protect/onStatus 等回调方法，
# 混淆会改名导致回调失败，因此必须 keep。
-keep class com.proxy.android.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
