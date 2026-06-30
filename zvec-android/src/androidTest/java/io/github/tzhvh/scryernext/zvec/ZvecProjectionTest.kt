package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Issue 05: the public read path — [ZvecCollection.fetch] with projection +
 * `includeVector`, the `collect_docs` copy-out-then-free read path (the most
 * ownership-delicate JNI code in Phase 1), and the per-type value extraction.
 *
 * Engine behavior pinned by this suite (verified against the live engine before
 * this was written — see the `## Engine notes` at the bottom):
 *  - A **non-nullable** field omitted from `outputFields` is ABSENT from the
 *    returned `fields` map (absent = not requested).
 *  - A **nullable** field the engine normalizes back as null is always PRESENT
 *    (as `ZvecValue.Null`) — see the engine-note on `normalize_nullable_fields`.
 *  - `includeVector = false` (default) omits vector fields; `true` returns them.
 *  - An absent pk is silently omitted from the result (fetch never throws for a
 *    miss); result order is NOT guaranteed (index by pk, not position).
 *  - Every `ZvecValue` arm round-trips through the read path with the right type.
 */
@RunWith(AndroidJUnit4::class)
class ZvecProjectionTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Schema covering every read-path type arm. `note` is nullable (the
    // Null-vs-absent field); the rest are required (governed by projection only).
    // `emb_f32` is the vector the includeVector flag gates.
    private val schema = CollectionSchema(
        name = "screenshots",
        fields = listOf(
            FieldSchema("title", FieldType.STRING),
            FieldSchema("size", FieldType.INT64),
            FieldSchema("weight", FieldType.DOUBLE),
            FieldSchema("flag", FieldType.BOOL),
            FieldSchema("note", FieldType.STRING, nullable = true),
            FieldSchema("emb_f32", FieldType.VECTOR_FP32, dimension = 4,
                indexParams = IndexParams.FlatParams()),
            FieldSchema("emb_f16", FieldType.VECTOR_FP16, dimension = 4),
            FieldSchema("emb_i8", FieldType.VECTOR_INT8, dimension = 4),
        ),
    )

    /**
     * A projection returns ONLY the requested (non-nullable) fields: every other
     * required scalar is ABSENT from the returned `fields` map (absent = not
     * requested). This is the load-bearing projection contract — a gallery-
     * listing query loads 3–4 fields, never the whole doc.
     */
    @Test fun projectionReturnsOnlyRequestedNonNullableFields() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_proj_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert { pk = "p1"; doc("alpha") }

            val got = col.fetch(listOf("p1"), outputFields = listOf("title"))
                .singleByPk("p1")

            assertEquals("alpha", (got.fields["title"] as ZvecValue.Str).value)
            // Non-nullable scalars NOT in the projection are absent.
            assertFalse("size must be absent under the projection", got.fields.containsKey("size"))
            assertFalse("weight must be absent under the projection", got.fields.containsKey("weight"))
            assertFalse("flag must be absent under the projection", got.fields.containsKey("flag"))
            // Vector fields omitted because includeVector=false (default).
            assertFalse("emb_f32 must be absent with includeVector=false",
                got.fields.containsKey("emb_f32"))
        }
    }

    /**
     * `includeVector = false` (the load-bearing default) omits vector fields even
     * when `outputFields = null` (all scalar fields). A `true` default would
     * deserialize ~5 KB FP32 vectors on every list query — the OOM path the
     * projection was written to prevent.
     */
    @Test fun includeVectorFalseOmitsVectorEvenWithAllFields() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_novec_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert { pk = "v1"; doc("vecless") }

            // All scalar fields, vector explicitly excluded by the default.
            val got = col.fetch(listOf("v1")).singleByPk("v1")

            assertEquals("vecless", (got.fields["title"] as ZvecValue.Str).value)
            assertEquals(7L, (got.fields["size"] as ZvecValue.ScalarInt).value)
            assertEquals(true, (got.fields["flag"] as ZvecValue.ScalarBool).value)
            // No vector field is present.
            assertFalse("emb_f32 must be absent with includeVector=false",
                got.fields.containsKey("emb_f32"))
            assertFalse("emb_f16 must be absent with includeVector=false",
                got.fields.containsKey("emb_f16"))
            assertFalse("emb_i8 must be absent with includeVector=false",
                got.fields.containsKey("emb_i8"))
        }
    }

    /**
     * `includeVector = true` returns vector fields as their typed `VecF32`/
     * `VecF16`/`VecI8` arms with array equality (FP32 within float tolerance —
     * 0.1 stores as 0.10000000149…). This pins the vector read path: borrowed
     * pointer via `zvec_doc_get_field_value_pointer`, copied into a fresh JVM
     * array before the doc is freed.
     */
    @Test fun includeVectorTrueReturnsVectorsWithArrayEquality() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_vec_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert {
                pk = "vec1"
                string("title", "with vec")
                int64("size", 1L)
                float64("weight", 0.0)
                bool("flag", false)
                fieldNull("note")
                vectorF32("emb_f32", floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
                vectorF16("emb_f16", shortArrayOf(1, 2, 3, 4))
                vectorI8("emb_i8", byteArrayOf(10, 20, 30, 40))
            }

            val got = col.fetch(listOf("vec1"), includeVector = true).singleByPk("vec1")

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
     * `outputFields = null` returns all scalar fields; the vector fields are
     * present iff `includeVector`. The combination that nails the default-
     * semantics matrix: null-projection + includeVector=false is the safe
     * gallery-listing shape.
     */
    @Test fun nullProjectionReturnsAllScalarsVectorGatedByFlag() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_all_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert { pk = "a1"; doc("all fields") }

            // includeVector=false: all scalars, no vectors.
            val noVec = col.fetch(listOf("a1")).singleByPk("a1")
            assertTrue("title present", noVec.fields["title"] is ZvecValue.Str)
            assertTrue("size present", noVec.fields["size"] is ZvecValue.ScalarInt)
            assertTrue("weight present", noVec.fields["weight"] is ZvecValue.ScalarFloat)
            assertTrue("flag present", noVec.fields["flag"] is ZvecValue.ScalarBool)
            assertFalse("emb_f32 absent", noVec.fields.containsKey("emb_f32"))

            // includeVector=true: all scalars AND vectors.
            val withVec = col.fetch(listOf("a1"), includeVector = true).singleByPk("a1")
            assertTrue("emb_f32 present", withVec.fields["emb_f32"] is ZvecValue.VecF32)
            assertTrue("emb_f16 present", withVec.fields["emb_f16"] is ZvecValue.VecF16)
            assertTrue("emb_i8 present", withVec.fields["emb_i8"] is ZvecValue.VecI8)
        }
    }

    /**
     * Null-vs-absent: a field that is null in storage comes back as
     * `ZvecValue.Null` (NOT an absent key) when the engine surfaces it. The
     * engine's `normalize_nullable_fields_for_fetch` re-adds every schema-nullable
     * field that's absent as null, so `note` (nullable, written null) is always
     * present as `ZvecValue.Null` — whether or not it's in `outputFields`. This
     * pins that the SDK surfaces the engine's real null/absent distinction rather
     * than papering over it.
     */
    @Test fun storedNullFieldComesBackAsZvecValueNull() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_null_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert { pk = "n1"; doc("has null note") }  // note = stored null

            val got = col.fetch(listOf("n1")).singleByPk("n1")
            // `note` is nullable and was written null → ZvecValue.Null, not absent.
            assertEquals("stored null must be ZvecValue.Null, not an absent key",
                ZvecValue.Null, got.fields["note"])
        }
    }

    /**
     * Every scalar type arm round-trips through the read path with the right
     * `ZvecValue` arm and value — INT64→ScalarInt, DOUBLE→ScalarFloat,
     * BOOL→ScalarBool, STRING→Str. The JNI dispatch resolves each field's
     * data_type from the collection schema; this asserts the per-type getter
     * (`get_field_value_basic` for scalars) lands in the correct arm.
     */
    @Test fun scalarTypeArmsRoundTripToCorrectZvecValue() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_types_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert {
                pk = "t1"
                string("title", "typed")
                int64("size", 4_000_000_000L)
                float64("weight", 3.5)
                bool("flag", true)
                fieldNull("note")
                vectorF32("emb_f32", floatArrayOf(1f, 0f, 0f, 0f))
                vectorF16("emb_f16", shortArrayOf(0, 0, 0, 0))
                vectorI8("emb_i8", byteArrayOf(0, 0, 0, 0))
            }

            val got = col.fetch(listOf("t1")).singleByPk("t1")
            assertEquals("typed", (got.fields["title"] as ZvecValue.Str).value)
            assertEquals(4_000_000_000L, (got.fields["size"] as ZvecValue.ScalarInt).value)
            assertEquals(3.5, (got.fields["weight"] as ZvecValue.ScalarFloat).value, 1e-9)
            assertEquals(true, (got.fields["flag"] as ZvecValue.ScalarBool).value)
        }
    }

    /**
     * An absent pk is silently omitted from the result — fetch never throws for a
     * miss. A batch of [present, absent, present] returns exactly the two found
     * docs (no error, no placeholder). Order is NOT guaranteed (verified live),
     * so the assertion indexes by pk, not by input position.
     */
    @Test fun absentPkIsOmittedFromResultNotThrown() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_miss_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            col.insert { pk = "m1"; doc("one") }
            col.insert { pk = "m2"; doc("two") }

            val results = col.fetch(listOf("m1", "ghost", "m2"))

            assertEquals("exactly the two found docs", 2, results.size)
            val byPk = results.associateBy { it.pk }
            assertEquals(setOf("m1", "m2"), byPk.keys)
            assertNull("ghost must not appear", byPk["ghost"])
        }
    }

    /**
     * An empty `pks` list is a no-op `emptyList()` WITHOUT touching native (the C
     * fetch path is never reached). Records the Kotlin short-circuit: an empty
     * fetch is a success, not an error.
     */
    @Test fun emptyPksIsNoOpEmptyList() = runBlocking {
        val dir = File(ctx.cacheDir, "zvec_proj_empty_${System.nanoTime()}")
        ZvecCollection.createAndOpen(dir, schema).use { col ->
            assertEquals(emptyList<ZvecDoc>(), col.fetch(emptyList()))
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun List<ZvecDoc>.singleByPk(pk: String): ZvecDoc {
        val matches = filter { it.pk == pk }
        assertEquals("expected exactly one doc with pk=$pk", 1, matches.size)
        return matches.single()
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
     * Populate every REQUIRED field with a trivial value (zvec requires every
     * declared field present on write — verified against the live engine in issue
     * 03's suite). `note` is nullable, so it's a stored null here.
     */
    private fun ZvecDocBuilder.doc(title: String) {
        string("title", title)
        int64("size", 7L)
        float64("weight", 0.0)
        bool("flag", true)
        fieldNull("note")
        vectorF32("emb_f32", floatArrayOf(1f, 0f, 0f, 0f))
        vectorF16("emb_f16", shortArrayOf(0, 0, 0, 0))
        vectorI8("emb_i8", byteArrayOf(0, 0, 0, 0))
    }

    // ---- Engine notes (ground truth, pinned above) -----------------------
    //
    //  - `zvec_collection_fetch(pks, count, output_fields, output_field_count,
    //    include_vector, &docs, &found_count)` (c_api.h:3332): output_fields=NULL
    //    = all fields; otherwise the projection set. found_count = found docs.
    //  - No `zvec_doc_get_field_type` — the read getters require a caller-supplied
    //    data_type per field; the SDK resolves it from the collection schema.
    //  - `normalize_nullable_fields_for_fetch` (c_api.cc:6689): after every fetch
    //    the engine re-adds every schema-nullable field that's absent as null,
    //    REGARDLESS of the projection. So nullable fields are always present in
    //    fetched docs (as ZvecValue.Null if null); "absent = not requested" holds
    //    cleanly only for NON-nullable fields.
    //  - Absent pk: silently omitted (no error). Result order is NOT guaranteed
    //    (a [d1, ghost, d2] fetch can return [d2, d1]) — index by pk.
}
