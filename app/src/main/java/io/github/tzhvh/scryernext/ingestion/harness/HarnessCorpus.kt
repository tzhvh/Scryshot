/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.harness

import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import java.io.ByteArrayInputStream
import java.util.Random

/**
 * The harness's synthetic corpus factory (`INGESTION_ADHOC_REPRO_HARNESS.md` §9):
 * N deterministic [Candidate]s plus the matching repository rows the real
 * [io.github.tzhvh.scryernext.ingestion.ZvecWriteSink] needs (its Model-B
 * invariant: a row must exist per locator or the write is skipped).
 *
 * Byte payloads default to small deterministic arrays — arms A0/A1 exercise the
 * engine/zvec/Room axes and deliberately carry **no** bitmap payload (§10: tiny
 * synthetic bytes would make H2 invisible, which is exactly why A2/A3 swap in a
 * real-image corpus via [bytesFor]).
 *
 * Locators are `content://harness/<i>` — stable, parseable back to the doc index
 * (the fake OCR stage derives its canned text from the same index), and distinct
 * from every production URI scheme.
 */
object HarnessCorpus {

    const val LOCATOR_PREFIX = "content://harness/"
    const val COLLECTION_ID = "harness"

    fun locatorFor(index: Int): String = "$LOCATOR_PREFIX$index"

    /** Inverse of [locatorFor]; -1 for non-harness locators. */
    fun indexFor(locator: String?): Int =
        locator?.takeIf { it.startsWith(LOCATOR_PREFIX) }
            ?.removePrefix(LOCATOR_PREFIX)?.toIntOrNull() ?: -1

    /**
     * The deterministic per-index byte payload (2048 bytes of seeded pseudo-random
     * content — distinct per index so the sink's SHA-256 content hashes never
     * collide and dedup cannot collapse the corpus).
     */
    fun syntheticBytes(index: Int): ByteArray {
        val random = Random((31L * index) + 7L)
        return ByteArray(BYTES_PER_DOC).also { random.nextBytes(it) }
    }

    /**
     * N candidates. [bytesFor] maps the doc index to its bytes — override for the
     * A2/A3 arms (real image bytes from the on-device corpus); the default is the
     * small synthetic payload. Each `byteHandle` produces a fresh stream (the
     * engine opens it at most once per candidate, but a retried debug run must
     * not inherit a consumed stream).
     */
    fun candidates(
        n: Int,
        bytesFor: (Int) -> ByteArray = ::syntheticBytes,
    ): List<Candidate> = (0 until n).map { i ->
        Candidate(
            locator = locatorFor(i),
            byteHandle = { ByteArrayInputStream(bytesFor(i)) },
        )
    }

    /**
     * The matching repository rows — one `processed = false` [ScreenshotModel]
     * per candidate so [io.github.tzhvh.scryernext.repository.ScreenshotRepository.getScreenshotByUri]
     * (the sink's Model-B lookup) resolves. Without a row per locator the real
     * sink's missing-row guard **skips every write silently** — an arm would run
     * "green" while writing nothing. Sizes/timestamps are deterministic per index
     * (the metadata cache keys on the `(locator, mtime, size)` triple).
     */
    fun screenshotRows(
        n: Int,
        sizeFor: (Int) -> Long = { syntheticBytes(it).size.toLong() },
    ): List<ScreenshotModel> =
        (0 until n).map { i ->
            ScreenshotModel(
                id = "harness-$i",
                uri = locatorFor(i),
                displayName = "harness_$i.png",
                size = sizeFor(i),
                lastModified = 1_700_000_000_000L + i,
                collectionId = COLLECTION_ID,
            )
        }

    /** The OCR text the fake/decode-only stages "recognize" for doc [index]. */
    fun ocrTextFor(index: Int, seed: Long = DEFAULT_SEED): String =
        SyntheticOcrText.document(index, Random(seed + index))

    const val DEFAULT_SEED = 2026_0923L

    private const val BYTES_PER_DOC = 2048
}
