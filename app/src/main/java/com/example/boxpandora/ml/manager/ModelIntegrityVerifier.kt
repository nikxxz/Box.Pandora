package com.example.boxpandora.ml.manager

import java.io.File
import java.security.MessageDigest

/**
 * Verifies model files by computing their SHA-256 and comparing against
 * the expected value from the manifest.
 *
 * Returns true only when the computed digest matches [expectedSha256] exactly.
 * If [expectedSha256] is blank the check is skipped and true is returned —
 * this covers Phase 1 development where binaries are sideloaded without a
 * known hash. Once hashes are populated in the manifest, the blank-skip
 * path will no longer be reachable for production models.
 */
object ModelIntegrityVerifier {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * Returns true if [file] matches [expectedSha256] (case-insensitive hex).
     * Returns true unconditionally when [expectedSha256] is blank.
     */
    fun verify(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().buffered(BUFFER_SIZE).use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val computed = digest.digest().joinToString("") { "%02x".format(it) }
        return computed.equals(expectedSha256, ignoreCase = true)
    }
}
