package dev.licensesystem.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateLicenseSdkResourceTask : DefaultTask() {
    @get:Input
    abstract val publicKey: Property<String>

    @get:Input
    abstract val productKey: Property<String>

    @get:Input
    abstract val timeoutMs: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val resolvedPublicKey = publicKey.get().trim()
        val resolvedProductKey = productKey.orNull?.trim().orEmpty().ifBlank {
            LicensePublicKeyDecoder.productKey(resolvedPublicKey)
        }

        val outputFile = outputDirectory.file("license-sdk.properties").get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            buildString {
                appendLine("license.public-key=$resolvedPublicKey")
                appendLine("license.product-key=$resolvedProductKey")
                appendLine("license.timeout-ms=${timeoutMs.get()}")
            }
        )
    }
}
