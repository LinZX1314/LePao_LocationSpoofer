import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.kdzw8p.mqa.v71f.xposed"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // NDK r28c 装在 C:\win-app\android-ndk-r28c（local.properties 的 ndk.dir 指向它）
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake {
                // 只编 arm64-v8a（真机目标）；需要模拟器验证时加 "x86_64"
                abiFilters += "arm64-v8a"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("native/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // 与 app/build.gradle.kts 中的 scheme 维度保持一致。
    // META-INF/xposed/scope.list 按 flavor 放在 src/scoped/resources 与 src/global/resources 下。
    flavorDimensions += "scheme"
    productFlavors {
        create("scoped") {
            dimension = "scheme"
            buildConfigField("boolean", "GLOBAL_SCHEME", "false")
        }
        create("global") {
            dimension = "scheme"
            buildConfigField("boolean", "GLOBAL_SCHEME", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-geo"))
    compileOnly(libs.xposed.api)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
