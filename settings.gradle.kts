pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

// botcore: 纯 Kotlin/JVM 协议层，不依赖 Android，可独立编译与单元测试
// app:     Android 壳（前台服务 + 数据层 + Compose 维护端）
include(":botcore")
include(":app")
