/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.util

import java.security.MessageDigest

/**
 * The zvec content_hash PK — SHA-256 over the file bytes, lowercase hex. THE single
 * production implementation: `ScreenshotDatabaseRepository.isKnown` (the dedup miss
 * path), `ZvecWriteSink.commit` (the fallback hash when the engine didn't thread one),
 * and DetailPage's single-file write all resolve identity through here, so every side
 * computes the same PK by construction, not by copy-paste agreement.
 *
 * Format contract: exactly what this function yields — 64 lowercase hex chars. Any
 * value crossing a seam as a "content hash" (e.g. [io.github.tzhvh.scryernext.ingestion.WriteSink.commit]'s
 * `precomputedContentHash`) must be a digest this function produced over the same bytes.
 * Nothing downstream validates it (zvec accepts any PK string), so the contract lives
 * here and in [ContentHashTest]'s golden vectors. Do not rewrite the hex encoding —
 * a naive `toString(16)` breaks on negative bytes and high-nibble padding, silently
 * changing every PK (the high-bit golden vector pins this).
 */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    // Hex-encode; the zvec PK is the content_hash string.
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
