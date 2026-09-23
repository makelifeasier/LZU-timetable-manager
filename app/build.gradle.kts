// 注意：Kotlin DSL 里 `java` 会被解析成 Java 插件扩展，遮住 java.util 包名，
// 所以必须用顶层 import 引入 Properties。
import java.util.Properties

// AGP 9.x 自带内置 Kotlin 编译（built-in Kotlin），无需再应用 org.jetbrains.kotlin.android。
plugins {
    id("com.android.application")
}

// 签名配置从 keystore.properties 读取；文件不存在时 release 走未签名（仅影响 assembleRelease）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.timetable"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.timetable"
        minSdk = 31
        targetSdk = 36
        versionCode = 26
        versionName = "3.2.1"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 注意：lint 的 WrongViewCast **拦不住** findViewById<Button>(R.id.x) 这种
        // 显式泛型写法（实测无效）。真正能拦住的是 ViewBinding —— 它按布局 XML
        // 生成强类型字段，类型对不上直接编译报错。lint 仍保留用于抓其他问题。
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
    }

    buildFeatures {
        // 按布局生成强类型绑定：控件类型与 XML 不符会变成编译错误，
        // 而不是运行到那一行才 ClassCastException 闪退。
        viewBinding = true
    }
}

// 打正式包前先跑 lint
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("lintRelease")
}

// 内置 Kotlin 由 AGP 负责把 compileOptions 的 jvm 目标接到 Kotlin 编译器，无需单独配置。

dependencies {
    testImplementation("junit:junit:4.13.2")
}
