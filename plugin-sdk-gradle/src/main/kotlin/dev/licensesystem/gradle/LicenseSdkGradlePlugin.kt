package dev.licensesystem.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.Properties

class LicenseSdkGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java")

        val extension = project.extensions.create("licenseSdk", LicenseSdkExtension::class.java)
        val task = project.tasks.register("generateLicenseSdkResource", GenerateLicenseSdkResourceTask::class.java) { generator ->
            generator.description = "Generates license-sdk.properties bundled into the plugin jar."
            generator.group = "build setup"
            generator.publicKey.set(extension.publicKey)
            generator.productKey.set(extension.productKey)
            generator.timeoutMs.set(extension.timeoutMs)
            generator.outputDirectory.set(project.layout.buildDirectory.dir("generated/license-sdk"))
        }

        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain
            .languageVersion
            .set(JavaLanguageVersion.of(21))

        project.afterEvaluate {
            if (extension.addDefaultRepositories.getOrElse(true)) {
                ensureMavenLocal(project)
                ensureMavenCentral(project)
                ensureMaven(project, "papermc", "https://repo.papermc.io/repository/maven-public/")
            }

            if (extension.addSdkDependency.getOrElse(true)) {
                val notation = "dev.licensesystem:plugin-sdk:${pluginSdkVersion()}"
                project.dependencies.add("implementation", notation)
            }
        }

        project.extensions.getByType(SourceSetContainer::class.java)
            .getByName("main")
            .resources
            .srcDir(task.flatMap { it.outputDirectory })

        project.tasks.named("processResources").configure { processResources ->
            processResources.dependsOn(task)
        }

        project.tasks.named("jar", Jar::class.java).configure { jar ->
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            jar.from(
                project.provider {
                    project.configurations.getByName("runtimeClasspath")
                        .filter { dependency -> dependency.name.startsWith("plugin-sdk-") && dependency.extension == "jar" }
                        .map { dependency -> project.zipTree(dependency) }
                }
            )
        }
    }

    private fun pluginSdkVersion(): String {
        val properties = Properties()
        val stream = javaClass.classLoader.getResourceAsStream("license-sdk-gradle.properties")
            ?: error("Brakuje license-sdk-gradle.properties w plugin-sdk-gradle.")
        stream.use(properties::load)
        return properties.getProperty("pluginSdkVersion")?.trim().orEmpty().ifBlank {
            error("Brakuje pluginSdkVersion w license-sdk-gradle.properties.")
        }
    }

    private fun ensureMavenLocal(project: Project) {
        val hasRepo = project.repositories.any { repository ->
            repository is MavenArtifactRepository && repository.url.toString().contains(".m2")
        }
        if (!hasRepo) {
            project.repositories.mavenLocal()
        }
    }

    private fun ensureMavenCentral(project: Project) {
        val hasRepo = project.repositories.any { repository ->
            repository is MavenArtifactRepository && repository.url.toString().contains("repo.maven.apache.org")
        }
        if (!hasRepo) {
            project.repositories.mavenCentral()
        }
    }

    private fun ensureMaven(project: Project, name: String, url: String) {
        val hasRepo = project.repositories.any { repository ->
            repository is MavenArtifactRepository && repository.url.toString().removeSuffix("/") == url.removeSuffix("/")
        }
        if (!hasRepo) {
            project.repositories.maven { repo ->
                repo.name = name
                repo.setUrl(url)
            }
        }
    }
}
