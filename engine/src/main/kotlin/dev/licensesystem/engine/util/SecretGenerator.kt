package dev.licensesystem.engine.util

import java.security.SecureRandom

object SecretGenerator {
    private val random = SecureRandom()
    private const val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun generateToken(length: Int): String {
        return buildString {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }
}
