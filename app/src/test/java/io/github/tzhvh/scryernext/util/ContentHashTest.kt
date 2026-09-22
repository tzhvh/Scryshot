package io.github.tzhvh.scryernext.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer (golden-vector) tests for [sha256Hex] — the single production SHA-256
 * implementation behind the zvec content_hash PK (the cross-store identity shared by
 * `ScreenshotDatabaseRepository.isKnown`, `ZvecWriteSink.commit`, and DetailPage's
 * single-file write).
 *
 * The expected digests are literals from an independent source (FIPS 180-4's examples,
 * plus python-hashlib-derived vectors for the high-bit cases), NOT values produced by
 * calling the code under test — this file is the only place in the repo that pins the
 * hash *format* (lowercase hex, two digits per byte, `and 0xff` masking). Every other
 * sha256 in the codebase (including the deliberate copies kept in test fakes as
 * independent oracles) must agree with these vectors.
 */
class ContentHashTest {

    @Test
    fun emptyInput_matchesFipsVector() {
        // SHA-256("") per FIPS 180-4 / NIST examples.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun abc_matchesFipsVector() {
        // SHA-256("abc") per FIPS 180-4 example B.1.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun highBitBytes_areMaskedAndZeroPadded() {
        // Bytes ≥ 0x80 pin the two failure modes of a naive toString(16) rewrite: a
        // negative byte must not sign-extend (the `and 0xff` mask) and a nibble < 0x10
        // must render two digits ("0a", not "a") — otherwise every PK silently changes.
        assertEquals(
            "89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3",
            sha256Hex(byteArrayOf(0x00.toByte(), 0x7f.toByte(), 0x80.toByte(), 0xff.toByte())),
        )
    }

    @Test
    fun outputIs64LowercaseHexChars() {
        val digest = sha256Hex(byteArrayOf(1, 2, 3))
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
