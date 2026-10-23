// 顶层构建脚本不声明插件：各模块在自己的 plugins {} 里按需声明
// （app：Android / Compose / KSP；botcore：Kotlin JVM / serialization）。
//
// 这样做的直接好处：CI/沙箱用 settings-botcore.gradle.kts 只测协议层时，
// 顶层不会去解析 AGP，无需 Android SDK 或 AGP 离线缓存。
