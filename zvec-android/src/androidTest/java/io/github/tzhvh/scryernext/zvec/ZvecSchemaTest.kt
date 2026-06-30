package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 02: schema construction + `createAndOpen`. Exercises the typed
 * [CollectionSchema]/[FieldSchema]/[IndexParams] data classes through the real
 * `.so`, and pins the load-bearing **fail-fast-before-disk-write** assertion
 * (Q8): an invalid schema throws [ZvecException] from the C validator *before*
 * `zvec_collection_create_and_open` touches disk, leaving no directory behind.
 *
 * Each test uses a unique collection path — zvec is a process-wide singleton, and
 * config/paths from one test persist for the next in the same process.
 */
@RunWith(AndroidJUnit4::class)
class ZvecSchemaTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A unique cache-scoped collection path per call. */
    private fun freshPath(): File = File(ctx.cacheDir, "zvec_schema_${System.nanoTime()}")

    /**
     * `createAndOpen` with a multi-field schema (string + int64 + FP32 vector with
     * an HNSW index) succeeds, returns a non-closed handle, and creates the
     * collection directory on disk.
     */
    @Test fun createAndOpenMultiFieldSchema() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "screenshots",
            fields = listOf(
                FieldSchema("display_name", FieldType.STRING, nullable = false),
                FieldSchema("size", FieldType.INT64, nullable = false),
                FieldSchema(
                    name = "embedding",
                    type = FieldType.VECTOR_FP32,
                    nullable = false,
                    dimension = 8,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200),
                ),
            ),
        )
        val col = ZvecCollection.createAndOpen(dir, schema)
        try {
            assertFalse("freshly-opened collection is not closed", col.isClosed)
            assertTrue("createAndOpen must materialize the collection dir", dir.exists())
        } finally {
            col.close()
            assertTrue("close flips isClosed", col.isClosed)
        }
    }

    /**
     * The load-bearing assertion (Q8): an invalid schema — a vector field missing
     * [FieldSchema.dimension] (encoded as 0, rejected by the C validator at
     * `schema.cc:80` with "dimension must be in (0,20000]") — throws
     * [ZvecException] with [ZvecErrorCode.INVALID_ARGUMENT] and a non-null
     * [ZvecException.detail], **and leaves no collection directory on disk**.
     *
     * `zvec_collection_schema_validate` runs after the C schema is built but before
     * `zvec_collection_create_and_open`. A directory present after the throw would
     * mean validation ran *after* the disk write — the exact bug this test guards.
     */
    @Test fun invalidSchemaThrowsBeforeDiskWrite() = runBlocking {
        val dir = freshPath()
        val invalid = CollectionSchema(
            name = "bad",
            fields = listOf(
                // Vector field with no dimension: dimension == 0 is rejected by the
                // C validator (schema.cc:80) with INVALID_ARGUMENT.
                FieldSchema("vec", FieldType.VECTOR_FP32, nullable = false, dimension = null),
            ),
        )
        var threw = false
        try {
            ZvecCollection.createAndOpen(dir, invalid)
        } catch (e: ZvecException) {
            threw = true
            assertEquals(ZvecErrorCode.INVALID_ARGUMENT, e.code)
            assertNotNull("detail must carry the validator's reason", e.detail)
            assertTrue(
                "detail should mention the dimension failure, was: ${e.detail}",
                e.detail!!.contains("dimension", ignoreCase = true),
            )
        }
        assertTrue("invalid schema must throw ZvecException", threw)
        assertFalse(
            "validation must run BEFORE the disk write — no dir after the throw",
            dir.exists(),
        )
    }

    /**
     * `createAndOpen` errors on a pre-existing path: the create path is create-
     * only (reopen with [ZvecCollection.open]).
     *
     * **Engine semantics, pinned by this test:** zvec surfaces this as
     * [ZvecErrorCode.INVALID_ARGUMENT], not `ALREADY_EXISTS` — the path-existence
     * check lives in `RocksdbContext::validate_and_set_db_path`
     * (`rocksdb_context.cc:220-223`), which returns `Status::InvalidArgument()`
     * when the path already exists and `should_exist=false`. The issue spec said
     * "errors if path exists" without pinning the code; this test does.
     */
    @Test fun createOnExistingPathErrorsInvalidArgument() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "once",
            fields = listOf(FieldSchema("title", FieldType.STRING, nullable = false)),
        )
        ZvecCollection.createAndOpen(dir, schema).use { /* create once */ }

        var threw = false
        try {
            ZvecCollection.createAndOpen(dir, schema)
        } catch (e: ZvecException) {
            threw = true
            // zvec maps the "path already exists" path check to INVALID_ARGUMENT
            // (rocksdb_context.cc:223), not ALREADY_EXISTS.
            assertEquals(ZvecErrorCode.INVALID_ARGUMENT, e.code)
        }
        assertTrue("creating on an existing path must throw", threw)
    }

    /**
     * FTS params round-trip through the flat-packed filter arrays: a field with an
     * FTS index + a tokenizer + filter list creates cleanly (the deepest params
     * build path — variable-length filter list encoded into the packed array).
     *
     * **Engine limitation, pinned by this test:** the pinned v0.5.1's
     * `TokenizerFactory::create_filter` (`tokenizer_factory.cc:97`) recognizes
     * exactly one filter name, `"lowercase"`; `stemmer`/`stopword`/etc. are
     * unknown and fail indexer creation with INTERNAL_ERROR at `open_fts_indexers`.
     * So this test exercises the recognized filter set. The SDK surfaces whatever
     * the engine does — an unknown filter is a developer error, not an SDK bug.
     */
    @Test fun ftsIndexWithFiltersCreatesCleanly() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "searchable",
            fields = listOf(
                FieldSchema(
                    name = "ocr_text",
                    type = FieldType.STRING,
                    nullable = false,
                    indexParams = IndexParams.FtsParams(
                        tokenizer = "standard",
                        // "lowercase" is the only filter the v0.5.1 factory knows
                        // (tokenizer_factory.cc:97). Unknown names fail indexer creation.
                        filters = listOf("lowercase"),
                    ),
                ),
                FieldSchema("tags", FieldType.STRING, nullable = true),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use {
            assertTrue("FTS schema create must succeed", dir.exists())
        }
    }

    // ---- Issue 09: stats + flush ---------------------------------------------
    // Folded into ZvecSchemaTest per the issue (no one-method test class). The
    // schema here is the stats-shaped one: a vector field (so the engine's
    // `index_completeness` map has an entry — collection.cc:406 iterates
    // vector_fields() ONLY) plus a scalar field (to pin that scalars are NOT in
    // the indexes list). docCount after inserts is what Phase 2's migration
    // progress UI reads.

    /**
     * The empty-collection stats: `docCount == 0`, and `indexes` reflects the
     * schema. The engine's `Stats()` reports completeness `1.0` for every vector
     * field on an empty collection (`collection.cc:410` — "if no doc,
     * completeness is 1"), so a freshly-created vector field lands here at `1.0`.
     *
     * **Pinned by this test:** the `indexes` list contains the VECTOR field and
     * NOT the scalar `size` field — the engine's `index_completeness` map is keyed
     * only by vector field names (`collection.cc:406`). The issue said "lists the
     * schema's indexed fields"; the source + live engine narrow that to vector
     * fields only, and this test holds the line.
     */
    @Test fun statsOnEmptyCollection() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "stats_empty",
            fields = listOf(
                FieldSchema("size", FieldType.INT64, nullable = false),
                FieldSchema(
                    name = "embedding",
                    type = FieldType.VECTOR_FP32,
                    nullable = false,
                    dimension = 8,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200),
                ),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            val stats = col.stats()
            assertEquals("empty collection reports zero docs", 0L, stats.docCount)
            assertEquals(
                "indexes lists VECTOR fields only — the scalar 'size' is absent",
                listOf("embedding"),
                stats.indexes.map { it.name },
            )
            // Engine convention (collection.cc:410): empty → completeness 1.0.
            stats.indexes.forEach { stat ->
                assertTrue(
                    "completeness must be in [0, 1], was ${stat.completeness}",
                    stat.completeness >= 0.0f && stat.completeness <= 1.0f,
                )
            }
        }
    }

    /**
     * After inserting N docs, `stats().docCount == N`. `flush()` returns without
     * throwing after writes (the engine's durability flush, `c_api.h:3016`).
     *
     * `docCount` is a uint64_t at the C boundary → `Long`; it counts LIVE docs
     * only (the engine sums per-segment `doc_count(delete_store_->make_filter())`,
     * `collection.cc:417`, so soft-deleted docs are excluded).
     */
    @Test fun statsDocCountAfterInsertsAndFlushNoThrow() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "stats_docs",
            fields = listOf(
                FieldSchema("title", FieldType.STRING, nullable = false),
                FieldSchema(
                    name = "embedding",
                    type = FieldType.VECTOR_FP32,
                    nullable = false,
                    dimension = 8,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200),
                ),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            repeat(3) { i ->
                col.insert {
                    pk = "doc$i"
                    string("title", "screenshot $i")
                    vectorF32("embedding", FloatArray(8) { it.toFloat() })
                }
            }

            // flush() is the durability knob: no-throw after writes.
            col.flush()

            val stats = col.stats()
            assertEquals("docCount reflects the inserts", 3L, stats.docCount)

            // The vector field appears with a completeness in [0, 1]. After inserts
            // the graph index may still be building (completeness < 1.0 is a real,
            // legal value — see IndexStat.completeness); we only assert the range +
            // that the right field is reported.
            val embStat = stats.indexes.singleOrNull { it.name == "embedding" }
            assertNotNull("the HNSW vector field is reported", embStat)
            assertTrue(
                "completeness in [0, 1], was ${embStat!!.completeness}",
                embStat.completeness >= 0.0f && embStat.completeness <= 1.0f,
            )
        }
    }

    // ---- Issue 10: runtime index DDL + optimize -----------------------------
    // Folded into ZvecSchemaTest per the issue (no one-method class). The DDL
    // surface reuses 02's IndexParamsGuard family via the shared build_index_params.
    //
    // ⚠ Engine behavior (verified against the pinned v0.5.1 source), narrowing
    // the issue's "createIndex … assert it appears in stats().indexes":
    // `stats().indexes` lists VECTOR fields by DATA TYPE (collection.cc:406
    // iterates schema_->vector_fields()), NOT by index presence. So a vector
    // field is in `indexes` whether or not it has an index — createIndex/dropIndex
    // change the index's EXISTENCE (and the completeness value), not field
    // presence. The real observable of a created vector index is that a vector
    // `query` against that field returns ranked results. These tests assert that.

    /**
     * `createIndex` builds a working HNSW index on a vector field that had NO
     * index at schema time. The proof is behavioral, not in `stats()`: after
     * creating the index, a vector `query` against the field returns ranked
     * results nearest-first (the index exists and serves queries).
     *
     * Schema: a plain (unindexed) `emb` VECTOR_FP32 field. After inserts +
     * `createIndex("emb", HnswParams)`, querying `emb` for the inserted vectors
     * returns them ordered by cosine distance (R1's nearest-first convention).
     */
    @Test fun createIndexBuildsAWorkingVectorIndex() = runBlocking {
        val dir = freshPath()
        // `emb` is declared with NO indexParams — it's a vector field with no
        // index until createIndex lands one.
        val schema = CollectionSchema(
            name = "ddl_create",
            fields = listOf(
                FieldSchema("title", FieldType.STRING, nullable = false),
                FieldSchema("emb", FieldType.VECTOR_FP32, nullable = false, dimension = 4),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsert { pk = "near"; string("title", "n"); vectorF32("emb", floatArrayOf(1f, 0f, 0f, 0f)) }
            col.upsert { pk = "far"; string("title", "f"); vectorF32("emb", floatArrayOf(0f, 0f, 0f, 1f)) }

            // Create the HNSW index at runtime, then build it.
            col.createIndex("emb", IndexParams.HnswParams(m = 16, efConstruction = 200))
            col.optimize()

            // The created index serves a query: nearest-first (R1), proving the
            // index exists and works — not merely present in stats().
            val results = col.query(
                QueryRequest(field = "emb", vector = listOf(1f, 0f, 0f, 0f), topK = 2),
            )
            assertEquals("created index serves a 2-result query", 2, results.size)
            assertEquals("nearest doc ranks first", "near", results.first().pk)
        }
    }

    /**
     * A second `createIndex` with EQUAL params is a no-op success
     * (`collection.cc:475` — "equal index params" returns OK without rebuilding).
     * Pins the idempotent-recreate path so Phase 2 can safely retry createIndex.
     */
    @Test fun createIndexWithEqualParamsIsIdempotentNoOp() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "ddl_idem",
            fields = listOf(
                FieldSchema("emb", FieldType.VECTOR_FP32, nullable = false, dimension = 4),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            val params = IndexParams.HnswParams(m = 16, efConstruction = 200)
            col.createIndex("emb", params)
            // Second call with the SAME params: no-throw (engine short-circuits).
            col.createIndex("emb", params)
        }
    }

    /**
     * `optimize()` is the post-migration index-build trigger: after bulk inserts
     * it runs without throwing and the vector field's index completeness stays in
     * `[0, 1]`. Completeness reaching exactly `1.0` is build-timing-dependent (the
     * graph may finish before optimize returns or need a further pass), so this
     * asserts the safe bound + no-throw rather than `== 1.0`.
     */
    @Test fun optimizeAfterInsertsIsNoThrow() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "ddl_opt",
            fields = listOf(
                FieldSchema("emb", FieldType.VECTOR_FP32, nullable = false, dimension = 4,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200)),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            repeat(5) { i ->
                col.upsert { pk = "d$i"; vectorF32("emb", FloatArray(4) { (i + it).toFloat() }) }
            }
            // The load-bearing DDL method: no-throw after writes.
            col.optimize()

            val stat = col.stats().indexes.single { it.name == "emb" }
            assertTrue(
                "completeness in [0, 1] after optimize, was ${stat.completeness}",
                stat.completeness >= 0.0f && stat.completeness <= 1.0f,
            )
        }
    }

    /**
     * `dropIndex` on an indexed vector field succeeds (no-throw) and leaves the
     * field still listed in `stats().indexes` — confirming the issue-09 finding
     * that stats lists vector fields by data type, not by index presence. The
     * index's absence is observable behaviorally (a query on the dropped field
     * would fail to use an index), not via stats presence/absence.
     */
    @Test fun dropIndexOnIndexedVectorFieldSucceeds() = runBlocking {
        val dir = freshPath()
        val schema = CollectionSchema(
            name = "ddl_drop",
            fields = listOf(
                FieldSchema("emb", FieldType.VECTOR_FP32, nullable = false, dimension = 4,
                    indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200)),
            ),
        )
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            // DropIndex on a schema-declared index: rebuilds segments without it.
            col.dropIndex("emb")

            // The VECTOR field is STILL in stats.indexes (data-type-keyed, not
            // index-keyed) — the field didn't go away, only its index did.
            val names = col.stats().indexes.map { it.name }
            assertTrue(
                "vector field stays listed after dropIndex (stats is data-type-keyed): $names",
                names.contains("emb"),
            )
        }
    }
}
