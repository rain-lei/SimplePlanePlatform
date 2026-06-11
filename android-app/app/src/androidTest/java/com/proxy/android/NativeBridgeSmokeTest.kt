package com.proxy.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A1 验收用 instrumented smoke test。
 *
 * 在真机/模拟器上运行，断言 `NativeBridge.nativeVersion()` 返回非空字符串——
 * 这同时验证了：libplane_core.so 被正确打包进 APK、能被 System.loadLibrary 加载、
 * 且 JNI 导出符号与 Kotlin external 声明匹配。
 *
 * 运行：./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeSmokeTest {

    @Test
    fun nativeVersionIsNotEmpty() {
        val version = NativeBridge().nativeVersion()
        assertTrue("native version should not be empty", version.isNotEmpty())
    }
}
