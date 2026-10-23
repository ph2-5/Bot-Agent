// 仅用于在无 Android SDK 的 CI/沙箱环境里独立验证 botcore 协议层。
// 用法：gradle -c settings-botcore.gradle.kts :botcore:test
// 正式构建仍走 settings.gradle.kts，无需改动。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QBotMobile"
include(":botcore")
