plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

dependencies {
    implementation(gradleApi())
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("license-sdk-gradle.properties") {
        expand("version" to project.version.toString())
    }
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        create("licenseSdkPlugin") {
            id = "dev.licensesystem.plugin-sdk"
            implementationClass = "dev.licensesystem.gradle.LicenseSdkGradlePlugin"
            displayName = "License System Plugin SDK"
            description = "Generates license-sdk.properties for Minecraft plugin licensing."
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "plugin-sdk-gradle"
        }
    }
}
