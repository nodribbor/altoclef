pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net")
        mavenCentral()
        gradlePluginPortal()
    }
}


rootProject.name = "altoclef"
rootProject.buildFileName = "root.gradle.kts"

val versionDir = file("versions/1.21.11")
if (versionDir.exists()) {
    include(":1.21.11")
    project(":1.21.11").apply {
        projectDir = versionDir
        buildFileName = "../../build.gradle"
        name = "1.21.11"
    }
}