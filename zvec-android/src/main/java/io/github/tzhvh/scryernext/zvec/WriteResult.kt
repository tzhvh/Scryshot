package io.github.tzhvh.scryernext.zvec

/**
 * The result of a bulk write — the per-doc reporting surface the migration reads
 * (issue 04, Q10). Returned by [ZvecCollection.insertAll] / [upsertAll] /
 * [updateAll] / [deleteAll]; never produced by the single-doc ops, which throw
 * [ZvecException] directly (a single failure in a single-doc op *is* an
 * exception; wrapping it in a one-element `WriteResult` would be ceremony).
 *
 * The split is clean: **single → throws, bulk → per-doc result.** Phase 2's
 * migration re-ingests a 500-file SAF batch via the engine; a failed doc reports
 * its [index] + [code] + [detail] through [failures] rather than aborting the
 * whole batch. The migration-progress UI ("Analyzing 45 of 842…") reads
 * [successCount] incrementally.
 *
 * Pure immutable value type — GC-owned, no handle (ADR 0006). The JNI layer
 * returns parallel primitive arrays (per-doc code + message); the Kotlin side
 * assembles this, so neither type is constructed by native code and neither
 * needs a ProGuard keep-rule (unlike [ZvecException], which JNI constructs).
 *
 * @param successCount number of docs the engine accepted ([failures]-free).
 *   For a `*All` of `n` docs this is `n - failures.size`.
 * @param failures the per-doc failures, in input order. Empty on full success.
 */
data class WriteResult(
    val successCount: Int,
    val failures: List<DocWriteFailure>,
)

/**
 * One failed doc in a bulk write. ADR 0006 (typed value) + Q10 (per-doc result).
 *
 * @param index the doc's position in the input batch (matches the C
 *   `_with_results` contract: "result index corresponds to input document
 *   index", `c_api.h:3144`). Lets the caller point at *which* builder lambda
 *   failed without re-running the batch.
 * @param code the per-doc [ZvecErrorCode] (ALREADY_EXISTS / NOT_FOUND /
 *   INVALID_ARGUMENT / …). `fromInt` falls back to [ZvecErrorCode.UNKNOWN] for
 *   an unrecognized engine code.
 * @param detail the engine's per-doc message (zvec's per-doc `message`, never
 *   the formatted [ZvecException.message]) — null only if the engine set none.
 */
data class DocWriteFailure(
    val index: Int,
    val code: ZvecErrorCode,
    val detail: String?,
)
