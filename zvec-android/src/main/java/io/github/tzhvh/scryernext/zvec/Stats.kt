package io.github.tzhvh.scryernext.zvec

/**
 * Collection-level observability — the value-type read of
 * `zvec_collection_get_stats` (issue 09). Phase 2's migration-progress UI
 * ("Analyzing 45 of 842…") reads [docCount]; the per-index [indexes] list
 * reports vector-index build completeness.
 *
 * Pure immutable value type — GC-owned, no handle (ADR 0006, same pattern as
 * [ZvecDoc] / [WriteResult]). The JNI layer reads the C stats struct under a
 * `StatsGuard` (which frees it on any exit path — see the acceptance criteria),
 * copies [docCount] + the per-index (name, completeness) pairs into this Kotlin
 * struct, then frees the C handle; nothing escapes.
 *
 * **⚠ `indexes` lists VECTOR fields only** (engine behavior, verified against the
 * pinned `v0.5.1` source `collection.cc:406` + the live engine): `Stats()`
 * populates `index_completeness` by iterating `schema_->vector_fields()`, so a
 * scalar field with an INVERT or FTS index does **not** appear here. This is not
 * an SDK limitation — it is exactly what the engine reports. The list reflects
 * the schema's vector fields that have (or are getting) a graph/IVF index; an
 * unindexed vector field still appears (its completeness is whatever the engine
 * computes — see [IndexStat.completeness]).
 *
 * **Ordering is NOT guaranteed.** The C getters walk an `unordered_map`
 * (`c_api.cc:1278` advances an `unordered_map::iterator`); iteration order is a
 * hash order, not schema order. Index by [IndexStat.name], not by list position.
 *
 * @param docCount number of live documents in the collection (the engine sums
 *   per-segment `doc_count(delete_store_->make_filter())`, so soft-deleted docs
 *   are NOT counted — `collection.cc:417`). This is what Phase 2's migration
 *   progress bar reads.
 * @param indexes one entry per vector field, with its index-completeness. Empty
 *   for a schema with no vector fields. Order is unspecified (hash order).
 */
data class CollectionStats(
    val docCount: Long,
    val indexes: List<IndexStat>,
)

/**
 * One vector field's index-completeness, as reported by `Stats()`.
 *
 * @param name the vector field's name (matches the [FieldSchema] name in the
 *   collection's schema).
 * @param completeness the fraction of this field's vectors that are in its
 *   index, in `[0.0, 1.0]`. The engine sets this to `1.0` on an empty collection
 *   or when `doc_count == 0` (`collection.cc:410`/`:422` — "if no doc,
 *   completeness is 1"); otherwise it is `indexed_doc_count / doc_count`. After
 *   a batch of inserts the graph index builds incrementally, so this may read
 *   below `1.0` until the build catches up (issue 10's `optimize()` is the
 *   caller-side trigger to finish a post-migration index build). `0.0` is also
 *   the C getter's sentinel for an out-of-range index (`c_api.cc:1295`) — the
 *   SDK never reads out of range, so a real `0.0` here means genuinely unindexed.
 */
data class IndexStat(
    val name: String,
    val completeness: Float,
)
