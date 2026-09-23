// AGP 9.x 自带内置 Kotlin 编译（built-in Kotlin），无需再应用 org.jetbrains.kotlin.android，
// 否则会撞上 AGP 已注册的 `kotlin` 扩展。
plugins {
    id("com.android.application") version "9.3.0" apply false
}
