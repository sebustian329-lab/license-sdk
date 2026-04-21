package dev.licensesystem.engine.license

import java.security.SecureRandom

object LicenseKeyGenerator {
    private val random = SecureRandom()
    private const val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(): String {
        val parts = buildList {
            repeat(4) {
                add(buildString {
                    repeat(5) {
                        append(alphabet[random.nextInt(alphabet.length)])
                    }
                })
            }
        }

        return "LS-${parts.joinToString("-")}"
    }
}
