package dev.nutting.pocketllm.util

import java.security.MessageDigest

/**
 * Utility functions for hash calculations.
 */
object HashUtils {

    /**
     * Calculate SHA-256 hash of a string.
     *
     * @param text Input text to hash
     * @return Hex-encoded SHA-256 digest
     *
     * Example: "hello" → 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
     */
    fun calculateSha256(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculate SHA-256 hash of a byte array.
     */
    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify if a hash matches the expected value.
     */
    fun verifyHash(text: String, expectedHash: String): Boolean {
        val calculated = calculateSha256(text)
        return calculated.equals(expectedHash, ignoreCase = true)
    }
}
