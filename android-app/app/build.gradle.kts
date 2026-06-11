import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proxy.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxy.android"
        minSdk = 24            // Android 7.0（任务文档 0.2）
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包当前关注的 ABI；与 scripts/build-rust.sh 产出的 jniLibs 子目录对齐。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // jniLibs 由 cargo-ndk（scripts/build-rust.sh）写入 src/main/jniLibs/<abi>/libplane_core.so，
    // Gradle 默认会从 src/main/jniLibs 打包，这里显式声明以便阅读。
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// 把 Rust .so 的构建挂到 preBuild 之前，保证 assemble 时 jniLibs 已就绪。
// MVP 阶段：若本机缺 cargo-ndk，任务会失败提示——CI 中由专门步骤保证（见 Task Q3）。
// 通过 -PskipRustBuild=true 可跳过（例如已手动跑过 build-rust.sh）。
val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compile plane-core (Rust) into jniLibs via cargo-ndk"
    workingDir = rootProject.projectDir.parentFile // 仓库根目录（android-app 的上一级）
    val profile = if (project.hasProperty("rustRelease")) "release" else "debug"
    commandLine("bash", "scripts/build-rust.sh", profile)
}

tasks.named("preBuild") {
    if (!project.hasProperty("skipRustBuild")) {
        dependsOn(buildRust)
    }
}
