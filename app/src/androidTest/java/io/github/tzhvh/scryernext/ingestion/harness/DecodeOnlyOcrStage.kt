package io.github.tzhvh.scryernext.ingestion.harness

import android.graphics.BitmapFactory
import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.ingestion.OcrOutcome
import io.github.tzhvh.scryernext.ingestion.OcrStage
import java.util.concurrent.atomic.AtomicInteger

/**
 * The A2 arm's decode-only [OcrStage] (`INGESTION_ADHOC_REPRO_HARNESS.md` §4):
 * the real full-res `BitmapFactory.decodeByteArray` churn over real image bytes,
 * with **no** ML Kit in the loop — it adds exactly one accumulator (bitmap /
 * byte-array pressure) on top of A1, so a red here names H2. It is the arm the
 * naive real-OCR run cannot be substituted by: it separates H2 (decode/allocator
 * churn) from H3 (recognizer state).
 *
 * ## The no-`recycle()` default is load-bearing — for this arm
 *
 * Production (`MlKitOcrStage.recognizeInternal`) recycles its per-file bitmap in
 * a `finally` since ingestion-cliff issue `05` — post-diagnosis hygiene, not part
 * of the cliff fix. This arm deliberately keeps the *pre-fix* profile it was
 * built to test ([recycle] = false): its accumulator is full-res bitmap /
 * allocator churn, and recycling here would blunt exactly the signal the H2
 * question was asked against (whether the then-production, GC-only allocation
 * profile trips the cliff). Pass [recycle] = true to A/B a mitigation (§3's
 * "recycling bitmaps moves the cliff").
 *
 * Failures: a null decode (corrupt bytes) is the archetypal permanent-content
 * failure and is counted + mapped as such. Everything else — an
 * [OutOfMemoryError] above all — propagates *out of the stage* deliberately: the
 * engine does not wrap `ocr.attempt` in a catch (the stage adapter owns failure
 * mapping), so an escaping OOM surfaces as the run-level collection error that
 * is exactly H2's falsifiable signature (§3).
 */
class DecodeOnlyOcrStage(
    private val recycle: Boolean = false,
    private val textFor: (Int) -> String = { HarnessCorpus.ocrTextFor(it) },
) : OcrStage {

    val attempts: AtomicInteger = AtomicInteger()
    val decodeFailures: AtomicInteger = AtomicInteger()
    /** Total decoded pixels — the honest "how much bitmap churn happened" counter. */
    val decodedPixels: AtomicInteger = AtomicInteger()

    override suspend fun attempt(candidate: Candidate, bytes: ByteArray): OcrOutcome {
        attempts.incrementAndGet()
        // No OOM catch — see class KDoc: an escaping OOM is the H2 signal.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: run {
                decodeFailures.incrementAndGet()
                return OcrOutcome.PermanentContentFailure
            }
        decodedPixels.addAndGet(bitmap.width * bitmap.height)
        if (recycle) bitmap.recycle()
        // Canned text: the write payload stays realistic without any recognizer.
        return OcrOutcome.Success(textFor(HarnessCorpus.indexFor(candidate.locator)))
    }
}
