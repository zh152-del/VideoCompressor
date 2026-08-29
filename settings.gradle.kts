// ============================================================================
// 本地视频压缩器 - 工程设置
//
// 仓库顺序：国内镜像在前，官方仓库在后作为兜底。
// 直连 Google Maven（dl.google.com）在某些网络下会被限速到几十 KB/s，
// 导致依赖下载超时，所以优先走阿里云镜像。
// ============================================================================
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "VideoCompressor"
include(":app")
