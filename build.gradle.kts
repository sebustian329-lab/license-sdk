plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
    `maven-publish`
}

group = "dev.licensesystem"
version = "1.0.0"

allprojects {
    group = rootProject.group
    version = rootProject.version
}
