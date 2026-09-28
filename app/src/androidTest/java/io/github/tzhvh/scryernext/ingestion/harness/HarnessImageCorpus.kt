package io.github.tzhvh.scryernext.ingestion.harness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import io.github.tzhvh.scryernext.ingestion.Candidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The on-device real-image corpus — the A2/A3 arms' bitmap axis
 * (`INGESTION_ADHOC_REPRO_HARNESS.md` §10: "H2 needs real image bytes — tiny
 * synthetic bytes won't churn the bitmap allocator… A2/A3 must feed real
 * screenshot [sized] PNGs/JPEGs through `byteHandle`, or H2 is invisible").
 *
 * Generates N full-screenshot-resolution JPEGs, each carrying **two letters**
 * (the field-verification corpus shape: legible text, trivial OCR cost), then
 * hands them to the engine as candidates whose `byteHandle` is a real file read.
 * Every image is byte-distinct by construction — the letter pair, glyph position
 * and font size all vary deterministically with the doc index, so the sink's
 * SHA-256 content hashes never collide and dedup cannot collapse the corpus
 * (a collapsed corpus would dedup-skip its way to a fake green).
 *
 * Generation is parallel (a [Semaphore] bounds concurrent ARGB bitmaps at
 * [MAX_CONCURRENT_BITMAPS] × ~9.6 MB so the generator itself cannot OOM an old
 * device) and cached in the instrumentation target's cache dir keyed by the
 * full spec — a re-run with identical parameters re-uses the bytes instead of
 * re-encoding thousands of JPEGs.
 */
object HarnessImageCorpus {

    /** One corpus spec; the cache key is the rendered string. */
    data class Spec(
        val count: Int,
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
    ) {
        override fun toString(): String = "imgs-${width}x${height}-$count"
    }

    /**
     * Ensure the corpus exists (generating if needed) and return its directory.
     * Cache hit = directory present with exactly [Spec.count] files (a killed
     * generation leaves a partial dir, which fails the count and regenerates).
     */
    suspend fun ensure(context: Context, spec: Spec, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): File {
        val dir = File(context.cacheDir, spec.toString())
        val existing = dir.listFiles()?.size ?: 0
        if (existing == spec.count && spec.count > 0) {
            Log.i(TAG, "image corpus cache hit: $dir ($existing files)")
            return dir
        }
        dir.deleteRecursively()
        dir.mkdirs()
        Log.i(TAG, "image corpus generating ${spec.count} JPEGs @ ${spec.width}x${spec.height} → $dir")
        val started = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            val gate = Semaphore(MAX_CONCURRENT_BITMAPS)
            coroutineScope {
                val workers = (0 until spec.count).map { i ->
                    async {
                        gate.withPermit { encodeOne(dir, spec, i) }
                        if ((i + 1) % PROGRESS_EVERY == 0 || i + 1 == spec.count) {
                            onProgress(i + 1, spec.count)
                            Log.i(
                                TAG,
                                "image corpus: ${i + 1}/${spec.count} " +
                                    "(${(System.currentTimeMillis() - started) / 1000.0}s)"
                            )
                        }
                    }
                }
                workers.awaitAll()
            }
        }
        Log.i(TAG, "image corpus done in ${(System.currentTimeMillis() - started) / 1000.0}s")
        return dir
    }

    /**
     * N candidates reading the corpus. The byte handle is a *fresh* stream per
     * call (the engine opens it at most once, per Candidate's contract) over the
     * real file — the same MediaStore-shaped read the production candidate makes.
     */
    fun candidates(dir: File, n: Int): List<Candidate> = (0 until n).map { i ->
        val file = fileFor(dir, i)
        Candidate(
            locator = HarnessCorpus.locatorFor(i),
            byteHandle = { FileInputStream(file) },
        )
    }

    private fun fileFor(dir: File, index: Int): File = File(dir, "harness_%06d.jpg".format(index))

    /** The encoded file's byte size — the harness repository rows' `size` field. */
    fun fileSize(dir: File, index: Int): Long = fileFor(dir, index).length()

    /**
     * Two letters + deterministic per-index jitter → byte-unique JPEGs. The pair
     * advances base-26 with the index; position/size jitter breaks the 676-pair
     * collision ceiling so doc #10000 is not a byte-copy of doc #234.
     */
    private fun encodeOne(dir: File, spec: Spec, index: Int) {
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val first = ALPHABET[index % ALPHABET.length]
            val second = ALPHABET[(index / ALPHABET.length) % ALPHABET.length]
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = GLYPH_BASE_PX + (index % GLYPH_JITTER_STEPS) * GLYPH_JITTER_PX
                isFakeBoldText = index % 2 == 0
            }
            val x = JITTER_BASE_X + (index % JITTER_X_STEPS) * JITTER_X_PX
            val y = spec.height * JITTER_BASE_Y_FRACTION + ((index * 7) % JITTER_Y_STEPS) * JITTER_Y_PX
            canvas.drawText("$first$second", x, y, paint)
            File(dir, "tmp_%06d.jpg".format(index)).let { tmp ->
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (!tmp.renameTo(fileFor(dir, index))) {
                    error("rename failed for doc $index")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private const val TAG = "IngestionHarness"
    private const val DEFAULT_WIDTH = 1080
    private const val DEFAULT_HEIGHT = 2220
    private const val JPEG_QUALITY = 85
    private const val MAX_CONCURRENT_BITMAPS = 4
    private const val PROGRESS_EVERY = 500
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val GLYPH_BASE_PX = 620f
    private const val GLYPH_JITTER_PX = 12f
    private const val GLYPH_JITTER_STEPS = 9
    private const val JITTER_BASE_X = 120f
    private const val JITTER_X_STEPS = 17
    private const val JITTER_X_PX = 13f
    private const val JITTER_BASE_Y_FRACTION = 0.42f
    private const val JITTER_Y_STEPS = 23
    private const val JITTER_Y_PX = 9f
}
