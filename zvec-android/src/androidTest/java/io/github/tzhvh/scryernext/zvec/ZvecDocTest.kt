package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Issue 03: the typed write path — [ZvecDocBuilder] DSL, single-doc
 * insert / upsert / delete, and the per-type JNI dispatch table.
 *
 * The round-trip needs *some* read path; issue 05 (the projection fetch surface)
 * hasn't landed and there is no `zvec_doc_get_field_type`, so this uses the
 * issue-sanctioned minimal internal [ZvecCollection.fetchTyped] scratch helper
 * (caller-supplied type per field — the builder knows the types it wrote). 05's
 * real surface supersedes it; the test's intent (every arm round-trips; the
 * error codes are surfaced) is unchanged.
 *
 * FP32 round-trips with ~1e-7 error (0.1 stores as 0.10000000149…), so vector
 * and float asserts compare within a tolerance rather than exact equality.
 */
@RunWith(AndroidJUnit4::class)
class ZvecDocTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // The test collection covers every ZvecValue arm the write path supports.
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

    // The field names + caller-known types the scratch reader reads back, in
    // schema order. Mirrors the per-type dispatch the write path exercised.
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
     * Round-trip EVERY ZvecValue arm: build with the typed DSL, insert, fetch
     * back, and assert typed equality. Scalars compare exactly; vectors compare
     * content + tolerance (FP32's 0.1→0.10000000149… and the FP16/INT8 paths).
     */
    @Test fun roundTripsEveryValueArm() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_doc_rt_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert {
                pk = "doc1"
                string("title", "hello ocr")
                int32("count32", 7)
                int64("count64", 4_000_000_000L)
                float64("weight", 3.5)
                bool("flag", true)
                fieldNull("note")
                vectorF32("emb_f32", floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
                vectorF16("emb_f16", shortArrayOf(1, 2, 3, 4))
                vectorI8("emb_i8", byteArrayOf(10, 20, 30, 40))
            }

            val got = col.fetchTyped("doc1", readNames, readTypes)
            assertNotNull("doc must be found after insert", got)
            got!!
            assertEquals("doc1", got.pk)
            assertEquals("hello ocr", (got.fields["title"] as ZvecValue.Str).value)
            assertEquals(7L, (got.fields["count32"] as ZvecValue.ScalarInt).value)
            assertEquals(4_000_000_000L, (got.fields["count64"] as ZvecValue.ScalarInt).value)
            assertEquals(3.5, (got.fields["weight"] as ZvecValue.ScalarFloat).value, 1e-9)
            assertEquals(true, (got.fields["flag"] as ZvecValue.ScalarBool).value)
            // Stored null → ZvecValue.Null (not an absent key).
            assertEquals(ZvecValue.Null, got.fields["note"])
            assertVecF32Equals(
                floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
                (got.fields["emb_f32"] as ZvecValue.VecF32).value, 1e-6f)
            assertArrayEquals(
                shortArrayOf(1, 2, 3, 4),
                (got.fields["emb_f16"] as ZvecValue.VecF16).value)
            assertArrayEquals(
                byteArrayOf(10, 20, 30, 40),
                (got.fields["emb_i8"] as ZvecValue.VecI8).value)
        }
    }

    /**
     * `insert` twice with the same pk → [ZvecException] with
     * [ZvecErrorCode.ALREADY_EXISTS] (verified via the live engine: the bare C
     * insert returns ZVEC_OK and only bumps error_count, so the SDK MUST use the
     * `_with_results` variant to surface the real per-doc code). The detail must
     * be non-null — this regression-checks issue 01's exception surface end-to-end.
     */
    @Test fun insertDuplicatePkThrowsAlreadyExists() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_doc_dup_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert {
                pk = "dup"
                minimalDoc("first")
            }
            try {
                col.insert {
                    pk = "dup"
                    minimalDoc("second")
                }
                fail("second insert of an existing pk must throw ZvecException")
            } catch (e: ZvecException) {
                assertEquals(ZvecErrorCode.ALREADY_EXISTS, e.code)
                assertNotNull("ALREADY_EXISTS must carry a detail", e.detail)
            }
        }
    }

    /**
     * `upsert` of an existing pk succeeds (overwrites) — the upsert path must
     * surface ZVEC_OK and replace the stored values.
     */
    @Test fun upsertOverwritesExistingPk() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_doc_up_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert {
                pk = "up"
                minimalDoc("before")
            }
            // Upsert overwrites without ALREADY_EXISTS.
            col.upsert {
                pk = "up"
                minimalDoc("after")
            }
            val got = col.fetchTyped("up", readNames, readTypes)
            assertNotNull(got)
            assertEquals("after", (got!!.fields["title"] as ZvecValue.Str).value)
        }
    }

    /**
     * `delete` of a nonexistent pk → [ZvecException] with [ZvecErrorCode.NOT_FOUND]
     * (same `_with_results` discipline: the bare delete returns ZVEC_OK on a miss).
     */
    @Test fun deleteMissingPkThrowsNotFound() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_doc_del_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            try {
                col.delete("ghost")
                fail("delete of a missing pk must throw ZvecException")
            } catch (e: ZvecException) {
                assertEquals(ZvecErrorCode.NOT_FOUND, e.code)
            }
        }
    }

    /**
     * `delete` of an existing pk succeeds, and a subsequent fetch returns null
     * (the engine no longer has the doc).
     */
    @Test fun deleteRemovesExistingDoc() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_doc_del2_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert {
                pk = "rm"
                minimalDoc("gone soon")
            }
            col.delete("rm")
            assertNull("deleted doc must not be fetchable", col.fetchTyped("rm", readNames, readTypes))
        }
    }

    private fun assertVecF32Equals(expected: FloatArray, actual: FloatArray, tol: Float) {
        assertEquals("vector length", expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(
                "emb_f32[$i]: expected ~${expected[i]}, got ${actual[i]}",
                abs(expected[i] - actual[i]) <= tol,
            )
        }
    }

    /**
     * Populate every REQUIRED field with a trivial value. zvec validates field
     * PRESENCE on write regardless of nullability — a declared field is always
     * required (verified against the live engine: `field[...] is required but not
     * provided`) — so every insert/upsert in this suite must supply all of them
     * even when the assertion only cares about one scalar. `note` is nullable, so
     * it alone can be a stored null; the rest must carry values.
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
