package io.github.tzhvh.scryernext.zvec

import java.io.Closeable
import java.io.File

/**
 * The collection handle: an application-scoped [OwnedHandle] wrapper over a zvec
 * `zvec_collection_t*`. Exactly one long-lived instance per collection per app
 * launch; close it deliberately (it is the single ownership boundary on the data
 * path). ADR 0005, Q8.
 *
 * The companion constructors ([createAndOpen] / [open]) build all transient C
 * handles (schema, fields, index params, options) internally and return only this
 * handle — the caller never manages a schema or query handle.
 *
 * Every method is `suspend` and the SDK **never hops threads internally** (ADR
 * 0007): zvec C calls are uncancellable native blocking calls, so the caller owns
 * the dispatcher and the cancellation boundary. Wrap a one-off call in
 * `withContext(Dispatchers.IO) { ... }`.
 *
 * Lifecycle: call [ensureNotClosed] (via the data-path methods landing in later
 * issues) before touching the native handle; [close] is idempotent.
 */
class ZvecCollection internal constructor(
    private val handle: OwnedHandle,
) : Closeable {

    /** True after [close]; data-path methods throw once this is set. */
    val isClosed: Boolean get() = handle.isClosed

    override fun close() = handle.close()

    /**
     * @throws ZvecException if the collection is closed.
     * @throws kotlin.coroutines.cancellation.CancellationException if the caller
     *   coroutine is cancelled (no internal dispatcher hop, so the call is
     *   uncancellable mid-flight — ADR 0007).
     */
    private fun ensureNotClosed() {
        check(!handle.isClosed) { "ZvecCollection is closed" }
    }

    /**
     * The raw `jlong`-wrapped collection pointer. Internal — the data path uses it
     * to dispatch to [ZvecNative]; no handle type escapes the SDK.
     */
    internal fun nativeHandle(): Long {
        ensureNotClosed()
        return handle.ptr
    }

    // ---- Single-doc writes (issue 03) -----------------------------------
    // insert / upsert take the typed builder DSL; the JNI layer flattens it and
    // dispatches each field to zvec_doc_add_field_by_value with its exact per-type
    // byte size, then runs the `_with_results` variant to surface the real per-doc
    // code (the bare insert returns ZVEC_OK even on a duplicate pk). All suspend:
    // zvec calls are uncancellable native blocking calls, so the caller owns the
    // dispatcher and the cancellation boundary (ADR 0007) — the SDK never hops.
    //
    // These throw [ZvecException] directly from the JNI layer (Q10): the native
    // call constructs it with the per-doc code + message, so the Kotlin op just
    // lets it propagate — no swallow, no rewrap.

    /**
     * Insert a single document built by [build]. Fails with
     * [ZvecErrorCode.ALREADY_EXISTS] if [ZvecDocBuilder.pk] already exists.
     *
     * @throws ZvecException on a duplicate pk, a required-field/validation error,
     *   or any engine error.
     */
    suspend fun insert(build: ZvecDocBuilder.() -> Unit) {
        ensureNotClosed()
        val desc = ZvecDocBuilder().apply(build).build()
        write(desc, WriteOp.OP_INSERT)
    }

    /**
     * Upsert (insert-or-overwrite) a single document built by [build]. Succeeds
     * whether or not [ZvecDocBuilder.pk] already exists.
     *
     * @throws ZvecException on a validation error or any engine error.
     */
    suspend fun upsert(build: ZvecDocBuilder.() -> Unit) {
        ensureNotClosed()
        val desc = ZvecDocBuilder().apply(build).build()
        write(desc, WriteOp.OP_UPSERT)
    }

    private fun write(desc: DocDescriptor, op: Int) {
        ZvecNative.nativeDocWrite(
            handle = nativeHandle(),
            op = op,
            pk = desc.pk,
            fieldCount = desc.fieldCount,
            fieldNames = desc.fieldNames,
            kinds = desc.kinds,
            scalarIndex = desc.scalarIndex,
            isNull = desc.isNull,
            longs = desc.longs,
            doubles = desc.doubles,
            bools = desc.bools,
            strings = desc.strings,
            f32Vecs = desc.f32Vecs,
            f16Vecs = desc.f16Vecs,
            i8Vecs = desc.i8Vecs,
        )
    }

    /**
     * Delete a single document by [pk]. Fails with [ZvecErrorCode.NOT_FOUND] if
     * [pk] does not exist.
     *
     * @throws ZvecException on a missing pk or any engine error.
     */
    suspend fun delete(pk: String) {
        ensureNotClosed()
        ZvecNative.nativeDelete(nativeHandle(), pk)
    }

    // ---- Bulk writes (issue 04) -----------------------------------------
    // The bulk surface always returns a per-doc [WriteResult]; a failed doc in a
    // 500-file batch reports its index + code + detail instead of aborting the
    // whole batch (Q10). The single-doc ops above throw; the bulk ops report.
    //
    // All suspend, no internal dispatcher hop (ADR 0007) — the caller owns the
    // dispatcher and the cancellation boundary, exactly like the single-doc path.
    //
    // Empty batch short-circuit: an empty input returns
    // `WriteResult(0, emptyList())` in Kotlin WITHOUT touching native. zvec's
    // `_with_results` DML variants reject doc_count==0 with INVALID_ARGUMENT
    // (c_api.cc:6380/6450/6520/6594) — but an empty batch is a no-op success,
    // not an error, so the SDK must not forward it.

    /**
     * Bulk insert. Each element of [docs] builds one document via the typed
     * [ZvecDocBuilder] DSL (same builder as [insert]). Returns a per-doc
     * [WriteResult]: a doc whose pk already exists lands in [WriteResult.failures]
     * with [ZvecErrorCode.ALREADY_EXISTS] rather than aborting the batch.
     *
     * An empty [docs] list is a no-op success: `WriteResult(0, emptyList())`.
     *
     * @throws ZvecException on an engine/argument-level failure (not a per-doc
     *   one); per-doc failures are reported via the returned [WriteResult].
     */
    suspend fun insertAll(docs: List<ZvecDocBuilder.() -> Unit>): WriteResult =
        writeBulk(docs, WriteOp.OP_INSERT)

    /**
     * Bulk upsert (insert-or-overwrite). Same shape as [insertAll]; per-doc
     * failures (if any) land in [WriteResult.failures].
     */
    suspend fun upsertAll(docs: List<ZvecDocBuilder.() -> Unit>): WriteResult =
        writeBulk(docs, WriteOp.OP_UPSERT)

    /**
     * Bulk update. Unlike [upsertAll], update fails a doc whose pk does NOT
     * already exist (per-doc [ZvecErrorCode.NOT_FOUND] in [WriteResult.failures]).
     * Otherwise identical shape.
     */
    suspend fun updateAll(docs: List<ZvecDocBuilder.() -> Unit>): WriteResult =
        writeBulk(docs, WriteOp.OP_UPDATE)

    /**
     * Bulk delete by pk. A pk that does not exist lands in
     * [WriteResult.failures] with [ZvecErrorCode.NOT_FOUND] rather than throwing.
     * An empty [pks] list is a no-op success: `WriteResult(0, emptyList())`.
     *
     * @throws ZvecException on an engine/argument-level failure.
     */
    suspend fun deleteAll(pks: List<String>): WriteResult {
        ensureNotClosed()
        if (pks.isEmpty()) return WriteResult(0, emptyList())
        val raw = ZvecNative.nativeDeleteAll(nativeHandle(), pks.toTypedArray())
        return assembleWriteResult(pks.size, raw)
    }

    /**
     * Delete every document matching [filter]. The [filter] is a SQL
     * filter-expression subset (`title = 'x'`, `category = 'receipts'`, …) passed
     * verbatim to the engine.
     *
     * **Returns `Unit`.** The C engine does not report a deleted count —
     * `zvec_collection_delete_by_filter` (`c_api.h:3284`) has no count out-param
     * (despite its doc-comment claiming one); `collection.cc:1633` returns only
     * `Status::OK()`. Both reference bindings (Rust `Result<()>`, Go `error`)
     * surface only an error. A `stats().docCount` before/after diff was
     * considered and rejected: `stats()` is collection-global, and a concurrent
     * SAF-ingestion insert during the diff would corrupt the count — even to
     * negative. If you need the count, query it yourself around a quiescent
     * window; the SDK will not pretend the engine provided one.
     *
     * **⚠ Injection.** [filter] is raw SQL-ish text. If any part of it is user
     * input (a tag name, a collection id), it MUST be escaped first via
     * `ZvecFilters.escapeFilterValue` (issue 08) — never interpolate a raw user
     * value. Until 08 lands, only pass static, developer-controlled filters.
     *
     * @throws ZvecException on a malformed filter or any engine error.
     */
    suspend fun deleteByFilter(filter: String) {
        ensureNotClosed()
        ZvecNative.nativeDeleteByFilter(nativeHandle(), filter)
    }

    /**
     * Build + run a bulk insert/upsert/update. Each builder lambda is materialized
     * into its [DocDescriptor], the descriptors are re-packed into the per-doc
     * parallel arrays-of-arrays [ZvecNative.nativeDocWriteBulk] consumes, the
     * native call returns per-doc `(codes, messages)` parallel arrays, and
     * [assembleWriteResult] folds them into a [WriteResult]. Empty [docs] is a
     * no-op success (see class note: the C `_with_results` calls reject count==0).
     */
    private fun writeBulk(docs: List<ZvecDocBuilder.() -> Unit>, op: Int): WriteResult {
        ensureNotClosed()
        if (docs.isEmpty()) return WriteResult(0, emptyList())
        val descs = Array(docs.size) { i -> ZvecDocBuilder().apply(docs[i]).build() }
        val raw = ZvecNative.nativeDocWriteBulk(
            handle = nativeHandle(),
            op = op,
            pks = Array(docs.size) { descs[it].pk },
            fieldCounts = IntArray(docs.size) { descs[it].fieldCount },
            fieldNames = Array(docs.size) { descs[it].fieldNames },
            kinds = Array(docs.size) { descs[it].kinds },
            scalarIndex = Array(docs.size) { descs[it].scalarIndex },
            isNull = Array(docs.size) { descs[it].isNull },
            longs = Array(docs.size) { descs[it].longs },
            doubles = Array(docs.size) { descs[it].doubles },
            bools = Array(docs.size) { descs[it].bools },
            strings = Array(docs.size) { descs[it].strings },
            f32Vecs = Array(docs.size) { descs[it].f32Vecs },
            f16Vecs = Array(docs.size) { descs[it].f16Vecs },
            i8Vecs = Array(docs.size) { descs[it].i8Vecs },
        )
        return assembleWriteResult(docs.size, raw)
    }

    /**
     * Fold the native per-doc `(codes, messages)` parallel arrays into a
     * [WriteResult]. `raw` is `Array<Any?>` = `[0]: IntArray codes, [1]:
     * Array<String?> messages` (index-aligned to the input batch). Each non-OK
     * code becomes a [DocWriteFailure] at its input index; [WriteResult.successCount]
     * is the OK count (batch size minus failures). A per-doc code the enum
     * doesn't know maps to [ZvecErrorCode.UNKNOWN] via [ZvecErrorCode.fromInt].
     */
    private fun assembleWriteResult(batchSize: Int, raw: Array<Any?>): WriteResult {
        @Suppress("UNCHECKED_CAST")
        val codes = raw[0] as IntArray
        @Suppress("UNCHECKED_CAST")
        val messages = raw[1] as Array<String?>
        val failures = ArrayList<DocWriteFailure>(codes.size)
        var success = 0
        for (i in 0 until batchSize) {
            val code = codes[i]
            if (code == 0) {
                success++
            } else {
                failures += DocWriteFailure(
                    index = i,
                    code = ZvecErrorCode.fromInt(code),
                    detail = messages[i],
                )
            }
        }
        return WriteResult(successCount = success, failures = failures)
    }

    /**
     * Minimal typed fetch for issue 03's round-trip test — the "scratch read
     * helper" the issue sanctions when 05 (the projection fetch surface) hasn't
     * landed. There is no `zvec_doc_get_field_type`, so [fieldTypes] supplies the
     * caller-known type per field; the JNI layer reads each value with its getter
     * and returns a flat array this helper unpacks into a [ZvecDoc].
     *
     * Internal: 05's real projection surface supersedes this. Returns `null` when
     * [pk] is absent.
     */
    internal suspend fun fetchTyped(
        pk: String,
        fieldNames: Array<String>,
        fieldTypes: IntArray,
    ): ZvecDoc? {
        ensureNotClosed()
        val raw = ZvecNative.nativeFetchTyped(nativeHandle(), pk, fieldNames, fieldTypes)
            ?: return null
        return unpackFetchTyped(raw, fieldNames)
    }

    /**
     * Unpack the flat `Object[]` from [ZvecNative.nativeFetchTyped] into a typed
     * [ZvecDoc]. Layout: [0]=pk, [1]=score (null), then one boxed value per field
     * in [fieldNames] order. The JNI layer boxes each value to its JDK wrapper
     * (Boolean/Long/Double/String) or primitive array (float[]/short[]/byte[]);
     * a `null` slot is a stored null → [ZvecValue.Null]. This helper is the typed
     * construction the JNI layer deliberately keeps on the JVM side.
     */
    @Suppress("UNCHECKED_CAST")
    private fun unpackFetchTyped(raw: Any, fieldNames: Array<String>): ZvecDoc {
        val arr = raw as Array<Any?>
        val pk = arr[0] as String
        val score = arr[1] as? Float
        val fields = LinkedHashMap<String, ZvecValue>(fieldNames.size)
        fieldNames.forEachIndexed { i, name ->
            val v = arr[2 + i]
            fields[name] = boxToValue(v)
        }
        return ZvecDoc(pk = pk, score = score, fields = fields)
    }

    /**
     * Map one JNI-boxed fetch value to its [ZvecValue] arm. Shared by the
     * [fetchTyped] scratch reader and the public [fetch] read path (the per-type
     * `when` the JNI dispatch mirrors — one flat box→value mapping on the JVM
     * side). A null box is a stored null → [ZvecValue.Null].
     */
    private fun boxToValue(v: Any?): ZvecValue = when (v) {
        is Boolean -> ZvecValue.ScalarBool(v)
        is Long -> ZvecValue.ScalarInt(v)
        is Double -> ZvecValue.ScalarFloat(v)
        is String -> ZvecValue.Str(v)
        is FloatArray -> ZvecValue.VecF32(v)
        is ShortArray -> ZvecValue.VecF16(v)
        is ByteArray -> ZvecValue.VecI8(v)
        null -> ZvecValue.Null
        else -> error("unsupported fetch value: ${v::class.java}")
    }

    // ---- Fetch + projection (issue 05) ----------------------------------
    // The public read path. `collect_docs` runs in the JNI layer: it walks the
    // zvec_doc_t** result array, COPIES pk + score + each present field into a
    // flat Object[] row, then zvec_docs_free frees the whole array + every doc
    // (the value-type invariant, ADR 0006 — no doc handle escapes). There is no
    // zvec_doc_get_field_type, so the JNI layer resolves each field's data_type
    // from the collection schema; the public fetch() takes no caller types.
    //
    // All suspend, no internal dispatcher hop (ADR 0007) — zvec calls are
    // uncancellable native blocking calls, so the caller owns the dispatcher and
    // the cancellation boundary, exactly like the write path.

    /**
     * Fetch documents by primary key, with an optional field projection.
     *
     * Engine behavior (verified against the live engine before this shipped):
     *  - An **absent** pk is silently omitted from the result — fetch never
     *    throws for a missing pk. The returned list carries only the found docs.
     *  - **Result order is not guaranteed** (a `[d1, ghost, d2]` fetch may return
     *    `[d2, d1]`); index by [ZvecDoc.pk], not by input position.
     *  - A **nullable** field that is null in storage comes back as
     *    [ZvecValue.Null] (the engine re-adds every schema-nullable field that's
     *    absent, as null — `normalize_nullable_fields_for_fetch`, c_api.cc:6689),
     *    whether or not it was in [outputFields]. The "absent key = not requested"
     *    contract therefore holds cleanly only for **non-nullable** fields.
     *
     * @param pks primary keys to fetch. An empty list is a no-op `emptyList()`
     *   WITHOUT touching native (the C fetch rejects count==0; an empty batch is
     *   a success, not an error).
     * @param outputFields projection — `null` (default) = all fields; otherwise
     *   only the named fields are returned. Absent (non-nullable) keys in the
     *   returned [ZvecDoc.fields] = "not requested" under a projection.
     * @param includeVector **load-bearing default `false`** (Q11): vectors are
     *   the large payload (~5 KB × N for FP32 embeddings). The default must be
     *   "don't pull them unless asked" — the gallery-listing use case the
     *   projection exists for; `true` would deserialize full vectors on every
     *   list query, the OOM path the projection was written to prevent.
     *
     * @throws ZvecException on any engine error (a missing pk is NOT an error —
     *   it's omitted from the result).
     */
    suspend fun fetch(
        pks: List<String>,
        outputFields: List<String>? = null,
        includeVector: Boolean = false,
    ): List<ZvecDoc> {
        ensureNotClosed()
        if (pks.isEmpty()) return emptyList()
        val rows = ZvecNative.nativeFetch(
            handle = nativeHandle(),
            pks = pks.toTypedArray(),
            outputFields = outputFields?.toTypedArray(),
            includeVector = includeVector,
        )
        return ArrayList<ZvecDoc>(rows.size).apply {
            for (row in rows) {
                // Each row is a non-null Object[] (collect_docs emits one per
                // found doc); the Array<Any?> signature is only because JVM
                // arrays of Object are naturally nullable at the JNI boundary.
                add(unpackRow(row ?: error("null row from nativeFetch"), keepScore = false))
            }
        }
    }

    /**
     * Unpack one flat `Object[]` row (from the shared `collect_docs` JNI helper)
     * into a typed [ZvecDoc]. Layout (names inline, so the read path is correct
     * under a projection and the engine's nullable-field re-addition — the SDK
     * doesn't have to guess which field each slot is):
     *   [0]     = pk (String)
     *   [1]     = score (Float — the engine's raw value)
     *   [2]     = present-field count (Int)
     *   [3..]   = interleaved (name: String, value: boxed) pairs, one per field
     *
     * `collect_docs` emits the same row shape for fetch (issue 05) and query
     * (issue 06); this single unpacker serves both. The difference is whether
     * [ZvecDoc.score] is meaningful: a plain fetch returns 0.0 from the engine,
     * so [unpackRow] nulls it out for fetch and keeps it for query.
     */
    @Suppress("UNCHECKED_CAST")
    private fun unpackRow(row: Any, keepScore: Boolean): ZvecDoc {
        val arr = row as Array<Any?>
        val pk = arr[0] as String
        val fieldCount = arr[2] as Int
        val fields = LinkedHashMap<String, ZvecValue>(fieldCount)
        var slot = 3
        repeat(fieldCount) {
            val name = arr[slot] as String
            val value = boxToValue(arr[slot + 1])
            fields[name] = value
            slot += 2
        }
        // score is query-only; fetch surfaces null (see the KDoc above).
        val score = if (keepScore) arr[1] as? Float else null
        return ZvecDoc(pk = pk, score = score, fields = fields)
    }

    // ---- Query (issue 06) -----------------------------------------------
    // The single-vector / pure-FTS query surface. `vector_query_t` + `fts_t` are
    // the one place in the SDK where the caller NEVER sees a handle, even
    // transiently (Q7): the JNI layer builds the query handle, configures it,
    // runs zvec_collection_query, walks the result array via the shared
    // `collect_docs` (same as fetch — issue 05), copies each doc out into a flat
    // Object[] row, then the DocsGuard frees the whole array + every doc and the
    // VectorQueryGuard/FtsGuard free the query handles. Zero leak surface by
    // construction; no `use {}` at the call site.
    //
    // R1 DISCHARGED (see ## Comments in issue 06, verified against the live
    // engine): under COSINE the engine returns results ASCENDING by score, where
    // score = cosine DISTANCE (1 − cos θ), range [0,2]. So lower = more similar,
    // and ZvecDoc.score is the distance, not the similarity. Other metrics carry
    // their own direction (L2 distance is also lower=nearer; IP similarity is
    // higher=nearer) — the SDK surfaces the engine's raw value untouched and does
    // NOT normalize, because R1's job is to pin the convention, not paper over it.
    //
    // All suspend, no internal dispatcher hop (ADR 0007) — zvec calls are
    // uncancellable native blocking calls, so the caller owns the dispatcher and
    // the cancellation boundary, exactly like the read/write paths.

    /**
     * Run a single-vector or pure-FTS query, returning a finite ranked result
     * list (zvec returns a finite set, NOT an open-ended stream — divergence #1
     * from the roadmap sketch, so this returns `List`, not `Flow`).
     *
     * [QueryRequest.vector] / [QueryRequest.fts] select the modality (see
     * [QueryRequest]); exactly one must be set. The result is ordered by the
     * engine — under COSINE (the screenshots schema's metric) results come back
     * **nearest first** with [ZvecDoc.score] = cosine *distance* (lower = more
     * similar; range [0,2]). The SDK does not re-order or normalize the score.
     *
     * @param request query modality + field + topK + optional filter.
     * @param outputFields projection — `null` (default) = all fields; otherwise
     *   only the named fields are returned. Absent non-nullable keys in the
     *   returned [ZvecDoc.fields] = "not requested" under the projection (same
     *   semantics as [fetch]).
     *
     * @throws ZvecException if both [QueryRequest.vector] and [QueryRequest.fts]
     *   are null, or on any engine error.
     */
    suspend fun query(
        request: QueryRequest,
        outputFields: List<String>? = null,
    ): List<ZvecDoc> {
        ensureNotClosed()
        // Exactly one modality must be set (see QueryRequest). Both null is the
        // invalid case; both set is the "use hybridSearch" case. The JNI layer
        // treats vector != null as vector mode, so reject before touching native.
        val vectorMode = request.vector != null
        val ftsMode = request.fts != null
        when {
            !vectorMode && !ftsMode -> throw ZvecException(
                ZvecErrorCode.INVALID_ARGUMENT,
                "QueryRequest needs a vector or fts (both were null)",
            )
            vectorMode && ftsMode -> throw ZvecException(
                ZvecErrorCode.INVALID_ARGUMENT,
                "QueryRequest set both vector and fts; use hybridSearch for fusion",
            )
        }
        val rows = ZvecNative.nativeQuery(
            handle = nativeHandle(),
            field = request.field,
            vector = request.vector?.toFloatArray(),
            fts = request.fts,
            filter = request.filter,
            topK = request.topK,
            outputFields = outputFields?.toTypedArray(),
        )
        return ArrayList<ZvecDoc>(rows.size).apply {
            for (row in rows) {
                // collect_docs emits one non-null Object[] per result doc; the
                // Array<Any?> signature is only because JVM arrays of Object are
                // naturally nullable at the JNI boundary.
                add(unpackRow(row ?: error("null row from nativeQuery"), keepScore = true))
            }
        }
    }

    // ---- Hybrid search (issue 07) -------------------------------------------
    // The multi-query fusion surface: fuses multiple query legs (one vector +
    // one FTS, typically) via a reranker. `multi_query_t` + each leg's
    // `sub_query_t` are the second place in the SDK where the caller NEVER sees a
    // handle, even transiently (Q7): the JNI layer builds the multi-query, builds
    // + adds each sub-query (COPIED — see SubQuery), sets the reranker / topK /
    // filter / output_fields, runs zvec_collection_multi_query, and walks the
    // result array via the shared `collect_docs` (issue 05). Zero leak surface by
    // construction; no `use {}` at the call site.
    //
    // R3 DISCHARGED (verified against the live engine before this shipped): the
    // v0.5.1 FTS-sub-query + vector-sub-query fusion path works on our pin. RRF
    // and Weighted rerankers both fuse a vector leg + an FTS leg correctly; the
    // post-fusion topK is honoured. The fusion is now a proven surface — Phase 4
    // (hybrid tuning) carries zero fusion research risk.
    //
    // R3 score-semantics finding (verified live): fused scores are NOT raw
    // distances. RRF yields tiny 1/(k+rank)-summed values (≈0.03 on a 2-leg
    // query); Weighted yields a summed-weighted score whose scale mixes the legs'
    // raw scales (cosine distance ∈ [0,2] + BM25 ∈ [0,∞)). Both are DISTINCT from
    // R1's cosine-distance convention. The SDK surfaces the engine's raw fused
    // score untouched (no normalization) — Phase 4 interprets it.
    //
    // All suspend, no internal dispatcher hop (ADR 0007) — exactly like the
    // read/write/single-query paths.

    /**
     * Run a hybrid search fusing multiple query legs via a [reranker], returning a
     * finite ranked result list. Each [SubQuery] is one leg (a vector similarity
     * leg or an FTS leg); a typical hybrid is **one vector + one FTS** leg, fused
     * by [RrfReranker] (the default, robust to incomparable score scales).
     *
     * The result is ordered by the engine's fused score (highest first). Unlike
     * the single-vector [query], the fused score's scale depends on the reranker
     * (see the R3 finding above) — the SDK surfaces it raw.
     *
     * @param queries the query legs. At least one is required (empty throws
     *   [ZvecErrorCode.INVALID_ARGUMENT] before touching native). Each leg must
     *   carry exactly one of [SubQuery.vector] / [SubQuery.fts].
     * @param topK post-fusion result count (default 20). Distinct from each leg's
     *   [SubQuery.numCandidates] (the per-leg candidate count before fusion).
     * @param reranker fusion strategy; [RrfReranker] is the default.
     * @param filter optional SQL filter-expression subset, applied BEFORE search
     *   (same raw `const char*` SQL subset as [QueryRequest.filter]). **⚠
     *   Injection:** if any part is user input, escape it first via
     *   `ZvecFilters.escapeFilterValue` (issue 08) — never interpolate a raw user
     *   value. Until 08 lands, only pass static, developer-controlled filters.
     * @param outputFields projection — `null` (default) = all fields; otherwise
     *   only the named fields are returned (same semantics as [fetch] / [query]).
     *
     * @throws ZvecException if [queries] is empty, if a leg sets both/neither of
     *   [SubQuery.vector] / [SubQuery.fts], or on any engine error.
     */
    suspend fun hybridSearch(
        queries: List<SubQuery>,
        topK: Int = 20,
        reranker: Reranker = RrfReranker(),
        filter: String? = null,
        outputFields: List<String>? = null,
    ): List<ZvecDoc> {
        ensureNotClosed()
        if (queries.isEmpty()) {
            throw ZvecException(
                ZvecErrorCode.INVALID_ARGUMENT,
                "hybridSearch needs at least one sub-query (queries was empty)",
            )
        }
        // Each leg must carry exactly one payload (vector XOR fts). Reject before
        // touching native, mirroring query()'s exactly-one-of discipline.
        queries.forEachIndexed { i, q ->
            val hasVec = q.vector != null
            val hasFts = q.fts != null
            when {
                !hasVec && !hasFts -> throw ZvecException(
                    ZvecErrorCode.INVALID_ARGUMENT,
                    "sub-query[$i] (field=${q.field}) needs a vector or fts (both were null)",
                )
                hasVec && hasFts -> throw ZvecException(
                    ZvecErrorCode.INVALID_ARGUMENT,
                    "sub-query[$i] (field=${q.field}) set both vector and fts; a leg carries one payload",
                )
            }
        }
        val rows = ZvecNative.nativeMultiQuery(
            handle = nativeHandle(),
            subFields = Array(queries.size) { queries[it].field },
            subHasVector = BooleanArray(queries.size) { queries[it].vector != null },
            subVectors = Array(queries.size) { queries[it].vector?.toFloatArray() ?: FloatArray(0) },
            subFts = Array(queries.size) { queries[it].fts },
            subNumCands = IntArray(queries.size) { queries[it].numCandidates ?: 0 },
            rerankerMode = rerankerMode(reranker),
            rankConstant = (reranker as? RrfReranker)?.rankConstant ?: 0,
            weightedWeights = weightedWeights(reranker, queries),
            topK = topK,
            filter = filter,
            outputFields = outputFields?.toTypedArray(),
        )
        return ArrayList<ZvecDoc>(rows.size).apply {
            for (row in rows) {
                add(unpackRow(row ?: error("null row from nativeMultiQuery"), keepScore = true))
            }
        }
    }

    /** The C-side reranker selector: 0 = RRF, 1 = Weighted. Mirrors zvec_jni_doc. */
    private fun rerankerMode(reranker: Reranker): Int = when (reranker) {
        is RrfReranker -> RerankerMode.RERANKER_RRF
        is WeightedReranker -> RerankerMode.RERANKER_WEIGHTED
    }

    /**
     * Resolve a [WeightedReranker] field-keyed map to the POSITIONAL `double[]`
     * the C API expects, aligned to the sub-query order. A leg whose field is
     * absent from the map defaults to `0.0` (the engine zeroes its contribution),
     * so the map should name every leg's field. RRF rerankers pass an empty array
     * (the JNI layer ignores it in RRF mode).
     */
    private fun weightedWeights(reranker: Reranker, queries: List<SubQuery>): DoubleArray =
        if (reranker !is WeightedReranker) {
            DoubleArray(0)
        } else {
            DoubleArray(queries.size) { i ->
                // field-keyed → positional; absent field defaults to 0.0.
                (reranker.weights[queries[i].field] ?: 0.0f).toDouble()
            }
        }

    private object WriteOp {
        // Mirror of the OP_* constants in zvec_jni_doc.cc.
        const val OP_INSERT = 0
        const val OP_UPSERT = 1
        const val OP_UPDATE = 2
    }

    private object RerankerMode {
        // Mirror of the RERANKER_* constants in zvec_jni_doc.cc (issue 07).
        const val RERANKER_RRF = 0
        const val RERANKER_WEIGHTED = 1
    }

    companion object {
        /**
         * Create a new collection directory at [path] from [schema], opened for use.
         * Errors if [path] already exists (use [open] to reopen).
         *
         * **Implicit validation, before any disk write** (Q8): the C schema is built,
         * then `zvec_collection_schema_validate` runs, then — only on success —
         * `zvec_collection_create_and_open` touches disk. An invalid schema (e.g. a
         * vector field missing [FieldSchema.dimension]) throws [ZvecException] with
         * [ZvecErrorCode.INVALID_ARGUMENT] and a non-null [ZvecException.detail],
         * **and leaves no directory on disk**.
         *
         * @throws ZvecException on validation failure, a pre-existing path
         *   ([ZvecErrorCode.ALREADY_EXISTS]), or any engine error.
         */
        suspend fun createAndOpen(
            path: File,
            schema: CollectionSchema,
            options: CollectionOptions = CollectionOptions(),
        ): ZvecCollection {
            Zvec.ensureInitialized()
            val descriptor = SchemaDescriptor.encode(schema, options)
            val ptr = ZvecNative.nativeSchemaCreateAndOpen(
                path = path.absolutePath,
                schemaName = descriptor.schemaName,
                fieldCount = descriptor.fieldCount,
                fieldNames = descriptor.fieldNames,
                fieldDataTypes = descriptor.fieldDataTypes,
                fieldNullable = descriptor.fieldNullable,
                fieldDimensions = descriptor.fieldDimensions,
                fieldIndexTypes = descriptor.fieldIndexTypes,
                // HNSW / IVF (vector params).
                indexM = descriptor.indexM,
                indexEfConstruction = descriptor.indexEfConstruction,
                indexNList = descriptor.indexNList,
                indexNIters = descriptor.indexNIters,
                indexMetric = descriptor.indexMetric,
                // INVERT.
                indexEnableRangeOpt = descriptor.indexEnableRangeOpt,
                // FTS — variable-length, encoded into the separate arrays below.
                ftsTokenizer = descriptor.ftsTokenizer,
                ftsExtraParams = descriptor.ftsExtraParams,
                ftsFilterNames = descriptor.ftsFilterNames,
                ftsFilterFieldIndices = descriptor.ftsFilterFieldIndices,
                // Options.
                enableMmap = descriptor.enableMmap,
                readOnly = descriptor.readOnly,
                maxBufferSize = descriptor.maxBufferSize,
            )
            return wrapHandle(ptr)
        }

        /**
         * Reopen an existing collection at [path] (every app launch except the
         * first). Succeeds on a clean, previously-closed collection; errors
         * [ZvecErrorCode.NOT_FOUND] (or engine equivalent) on a nonexistent path.
         *
         * **No stale-`LOCK` recovery.** Crash-recovery heuristics (detect `lock` in
         * the error message, delete `$dbPath/LOCK`, retry once) are deliberately
         * out of scope — they're a caller policy about how aggressive to be after a
         * crash, and matching the error substring couples the SDK to zvec's error-
         * string format. Phase 2's repository layer owns that. See the phase doc's
         * "Handoff to Phase 2".
         *
         * @throws ZvecException on a nonexistent path, a corrupted collection, or
         *   any engine error.
         */
        suspend fun open(
            path: File,
            options: CollectionOptions = CollectionOptions(),
        ): ZvecCollection {
            Zvec.ensureInitialized()
            val ptr = ZvecNative.nativeCollectionOpen(
                path = path.absolutePath,
                enableMmap = options.enableMmap,
                readOnly = options.readOnly,
                maxBufferSize = options.maxBufferSize,
            )
            return wrapHandle(ptr)
        }

        private fun wrapHandle(ptr: Long): ZvecCollection {
            require(ptr != 0L) { "native create/open returned a null handle without throwing" }
            return ZvecCollection(
                NativeOwnedHandle(ptr) { ZvecNative.nativeClose(it) }
            )
        }
    }
}
