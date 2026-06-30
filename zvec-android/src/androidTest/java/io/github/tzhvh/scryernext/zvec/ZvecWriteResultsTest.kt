package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 04: the bulk write path — per-doc [WriteResult] reporting. Bulk ops
 * return per-doc results (a failed doc reports its index + code + detail) where
 * the single-doc ops (issue 03) throw. This is the migration's reporting
 * mechanism: a 500-file SAF batch reports *which* doc failed, not an aggregate
 * throw.
 *
 * Verified engine behaviour this suite pins:
 *  - The `_with_results` DML variants return one ordered result per input doc
 *    ("result index corresponds to input document index", c_api.h:3144), so
 *    [DocWriteFailure.index] is the input position. (Checked against the live
 *    engine before this suite was written.)
 *  - `delete_by_filter` returns NO count (c_api.h:3284 has no out-param; the SDK
 *    surfaces `Unit`). Its effect is verified here by subsequent fetch, not by a
 *    returned number.
 *
 * Reuses issue 03's schema (covers every [ZvecValue] arm) and `minimalDoc`
 * helper, plus the issue-03-sanctioned internal [ZvecCollection.fetchTyped]
 * scratch reader for read-back (issue 05's projection surface supersedes it).
 */
@RunWith(AndroidJUnit4::class)
class ZvecWriteResultsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Every ZvecValue arm — same schema as ZvecDocTest so the per-type paths stay
    // exercised through the bulk entry points too.
    private val schema = CollectionSchema(
        name = "screenshots",
        fields = listOf(
            FieldSchema("title", FieldType.STRING),
            FieldSchema("count32", FieldType.INT32),
            FieldSchema("count64", FieldType.INT64),
            FieldSchema("weight", FieldType.DOUBLE),
            FieldSchema("flag", FieldType.BOOL),
            FieldSchema("note", FieldType.STRING, nullable = true),
            FieldSchema("emb_f32", FieldType.VECTOR_FP32, dimension = 4,
                indexParams = IndexParams.FlatParams()),
            FieldSchema("emb_f16", FieldType.VECTOR_FP16, dimension = 4),
            FieldSchema("emb_i8", FieldType.VECTOR_INT8, dimension = 4),
        ),
    )

    // Caller-known types the scratch reader reads back, in schema order.
    private val readNames = arrayOf(
        "title", "count32", "count64", "weight", "flag",
        "note", "emb_f32", "emb_f16", "emb_i8",
    )
    private val readTypes = intArrayOf(
        FieldType.STRING.toNative(), FieldType.INT32.toNative(), FieldType.INT64.toNative(),
        FieldType.DOUBLE.toNative(), FieldType.BOOL.toNative(), FieldType.STRING.toNative(),
        FieldType.VECTOR_FP32.toNative(), FieldType.VECTOR_FP16.toNative(),
        FieldType.VECTOR_INT8.toNative(),
    )

    /**
     * Bulk `insertAll` where ONE doc has a duplicate pk. The engine accepts the
     * rest and reports exactly the duplicate's index + [ZvecErrorCode.ALREADY_EXISTS]
     * + a non-null detail — not an aggregate throw. The valid docs must be
     * retrievable afterwards.
     *
     * **Per-doc vs. whole-batch abort — pinned engine behaviour** (verified against
     * the live engine before this test was written):
     *  - A DATA-level conflict (duplicate pk on insert, missing pk on update/
     *    delete) is reported per-doc via the `_with_results` array. This is what
     *    this test asserts.
     *  - A SCHEMA-level validation failure (e.g. a wrong-dimension vector) is a
     *    whole-batch abort: the C++ `Insert` validates all docs against the schema
     *    before writing, and a malformed doc makes it return an error, so
     *    `insert_with_results` returns an API-level error WITHOUT building per-doc
     *    results (`c_api.cc:6399-6402`). That surfaces as a thrown [ZvecException],
     *    not a [WriteResult] failure — see [batchAbortsOnSchemaLevelInvalidDoc].
     */
    @Test fun insertAllMixedSuccessReportsPerDocAlreadyExists() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_mix_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            // Pre-insert b3 so it collides with the batch's index-3 doc.
            col.insert { pk = "b3"; minimalDoc("pre-existing") }

            // index 0,1,2 are new (succeed); index 3 collides with the pre-inserted
            // b3 → ALREADY_EXISTS on that doc only. insertAll uses insert semantics,
            // so a pre-existing pk is the per-doc failure.
            val result = col.insertAll(listOf(
                { pk = "b0"; minimalDoc("zero") },
                { pk = "b1"; minimalDoc("one") },
                { pk = "b2"; minimalDoc("two") },
                { pk = "b3"; minimalDoc("collision") },
            ))

            assertEquals("successCount", 3, result.successCount)
            assertEquals("exactly one failure", 1, result.failures.size)
            val f = result.failures.single()
            assertEquals("failure index", 3, f.index)
            assertEquals("failure code", ZvecErrorCode.ALREADY_EXISTS, f.code)
            assertNotNull("ALREADY_EXISTS must carry a detail", f.detail)

            // The new docs were actually stored.
            listOf("b0", "b1", "b2").forEach { pk ->
                assertNotNull("$pk must be retrievable", col.fetchTyped(pk, readNames, readTypes))
            }
            // The colliding doc retains its pre-existing value (insert did not overwrite).
            val b3 = col.fetchTyped("b3", readNames, readTypes)
            assertNotNull(b3)
            assertEquals("pre-existing", (b3!!.fields["title"] as ZvecValue.Str).value)
        }
    }

    /**
     * A SCHEMA-level invalid doc (wrong-dimension vector) aborts the WHOLE batch:
     * the engine validates all docs before writing, so a malformed doc surfaces as
     * a thrown [ZvecException] ([ZvecErrorCode.INVALID_ARGUMENT]) — NOT a per-doc
     * [WriteResult] failure. This pins that whole-batch-abort behaviour so no one
     * later mistakes it for a missing per-doc result.
     */
    @Test fun batchAbortsOnSchemaLevelInvalidDoc() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_abort_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            try {
                col.upsertAll(listOf(
                    { pk = "good"; minimalDoc("ok") },
                    {
                        pk = "bad"
                        string("title", "wrong dim")
                        int32("count32", 0)
                        int64("count64", 0L)
                        float64("weight", 0.0)
                        bool("flag", false)
                        fieldNull("note")
                        // Wrong dimension: 3 floats, schema requires 4.
                        vectorF32("emb_f32", floatArrayOf(0.1f, 0.2f, 0.3f))
                        vectorF16("emb_f16", shortArrayOf(0, 0, 0, 0))
                        vectorI8("emb_i8", byteArrayOf(0, 0, 0, 0))
                    },
                ))
                error("a schema-level invalid doc must abort the batch with ZvecException")
            } catch (e: ZvecException) {
                assertEquals(ZvecErrorCode.INVALID_ARGUMENT, e.code)
                assertNotNull("the dimension mismatch must carry a detail", e.detail)
            }
            // The well-formed doc was NOT stored either — the batch aborted wholesale.
            assertNull("good must not be stored after a batch abort",
                col.fetchTyped("good", readNames, readTypes))
        }
    }

    /**
     * `deleteAll` over a mix of existing + nonexistent pks: per-doc
     * [ZvecErrorCode.NOT_FOUND] for the missing ones, [successCount] reflecting
     * the deleted, and the deleted pks no longer fetch.
     */
    @Test fun deleteAllMixedExistingAndMissingReportsPerDocNotFound() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_del_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsertAll(listOf(
                { pk = "d0"; minimalDoc("zero") },
                { pk = "d1"; minimalDoc("one") },
            ))

            // Delete two existing + two missing.
            val result = col.deleteAll(listOf("d0", "ghost0", "d1", "ghost1"))

            assertEquals("successCount", 2, result.successCount)
            assertEquals("two failures", 2, result.failures.size)
            val failedIndices = result.failures.map { it.index }.sorted()
            assertEquals("failed indices", listOf(1, 3), failedIndices)
            result.failures.forEach {
                assertEquals(ZvecErrorCode.NOT_FOUND, it.code)
            }

            assertNull("d0 deleted", col.fetchTyped("d0", readNames, readTypes))
            assertNull("d1 deleted", col.fetchTyped("d1", readNames, readTypes))
        }
    }

    /**
     * `deleteByFilter` deletes a known subset. Verified by subsequent fetch (the
     * engine returns no count — see the class KDoc and [ZvecCollection.deleteByFilter]).
     * The filter syntax is the SQL-expression subset the engine's SQL layer
     * consumes (`title = '...'`); confirmed against the live engine.
     */
    @Test fun deleteByFilterDeletesMatchingSubset() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_dbf_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsertAll(listOf(
                { pk = "k0"; minimalDoc("keep") },
                { pk = "g0"; minimalDoc("gone") },
                { pk = "g1"; minimalDoc("gone") },
            ))

            col.deleteByFilter("title = 'gone'")

            assertNotNull("non-matching doc survives", col.fetchTyped("k0", readNames, readTypes))
            assertNull("matching doc deleted", col.fetchTyped("g0", readNames, readTypes))
            assertNull("matching doc deleted", col.fetchTyped("g1", readNames, readTypes))
        }
    }

    /**
     * `deleteByFilter` with a filter matching nothing completes without error
     * (and changes nothing).
     */
    @Test fun deleteByFilterMatchingNothingSucceeds() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_dbf_none_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.upsertAll(listOf({ pk = "k0"; minimalDoc("keep") }))

            // No doc matches → no error, no deletion.
            col.deleteByFilter("title = 'nothing-matches-this'")

            assertNotNull("doc survives a no-op filter", col.fetchTyped("k0", readNames, readTypes))
        }
    }

    /**
     * An empty bulk batch is a no-op success — `WriteResult(0, emptyList())` —
     * WITHOUT touching native. zvec's `_with_results` DML variants reject
     * doc_count==0 with INVALID_ARGUMENT (c_api.cc:6380/6450/6520/6594), so the
     * SDK short-circuits empties in Kotlin: an empty batch is a success, not an
     * error. Records that engine-defined behaviour.
     */
    @Test fun emptyBatchIsNoOpSuccess() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_empty_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            assertEquals(
                WriteResult(0, emptyList()),
                col.insertAll(emptyList()),
            )
            assertEquals(
                WriteResult(0, emptyList()),
                col.upsertAll(emptyList()),
            )
            assertEquals(
                WriteResult(0, emptyList()),
                col.updateAll(emptyList()),
            )
            assertEquals(
                WriteResult(0, emptyList()),
                col.deleteAll(emptyList()),
            )
        }
    }

    /**
     * `updateAll` fails a doc whose pk does NOT already exist (per-doc
     * [ZvecErrorCode.NOT_FOUND]) — the difference from `upsertAll`, which would
     * insert it. Pins the OP_UPDATE arm.
     */
    @Test fun updateAllFailsMissingPkPerDoc() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_bulk_upd_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            // Pre-insert one; the update batch also targets a missing pk.
            col.upsertAll(listOf({ pk = "u0"; minimalDoc("before") }))

            val result = col.updateAll(listOf(
                { pk = "u0"; minimalDoc("after") },   // exists → OK
                { pk = "missing"; minimalDoc("x") },   // missing → NOT_FOUND per-doc
            ))

            assertEquals("successCount", 1, result.successCount)
            assertEquals("one failure", 1, result.failures.size)
            val f = result.failures.single()
            assertEquals("failure index", 1, f.index)
            assertEquals(ZvecErrorCode.NOT_FOUND, f.code)

            // The existing doc WAS updated (update, not no-op).
            val got = col.fetchTyped("u0", readNames, readTypes)
            assertNotNull(got)
            assertTrue("u0 was updated", got!!.fields["title"] is ZvecValue.Str)
            assertEquals("after", (got.fields["title"] as ZvecValue.Str).value)
        }
    }

    /**
     * Populate every REQUIRED field with a trivial value (same contract as
     * ZvecDocTest: zvec requires every declared field present on write).
     */
    private fun ZvecDocBuilder.minimalDoc(title: String) {
        string("title", title)
        int32("count32", 0)
        int64("count64", 0L)
        float64("weight", 0.0)
        bool("flag", false)
        fieldNull("note")
        vectorF32("emb_f32", floatArrayOf(1f, 0f, 0f, 0f))
        vectorF16("emb_f16", shortArrayOf(0, 0, 0, 0))
        vectorI8("emb_i8", byteArrayOf(0, 0, 0, 0))
    }
}
