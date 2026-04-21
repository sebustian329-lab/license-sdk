package dev.licensesystem.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class LicenseSdkExtension @Inject constructor(
    objects: ObjectFactory
) {
    val publicKey: Property<String> = objects.property(String::class.java)
    val productKey: Property<String> = objects.property(String::class.java).convention("")
    val timeoutMs: Property<Int> = objects.property(Int::class.java).convention(5_000)
    val addDefaultRepositories: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val addSdkDependency: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
