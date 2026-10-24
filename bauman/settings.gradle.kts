// Отдельная сборка: корневой проект репозитория (YT Фон) её не подключает.
// Собирать из корня репозитория: ./gradlew -p bauman assembleDebug
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

rootProject.name = "bauman"
