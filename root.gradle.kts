plugins {
    id("fabric-loom") version "1.8.+" apply false
}

subprojects {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}