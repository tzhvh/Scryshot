package io.github.tzhvh.scryernext.zvec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for the [SchemaDescriptor] encoder — the one pure-Kotlin seam in
 * issue 02. The `.so` can't run on the JVM (the same constraint that forced the
 * OcrStage seam in ADR 0004), so the schema-build JNI path is instrumentation-
 * only. But the *flattening* of the schema tree into parallel primitive arrays is
 * pure data manipulation, and a wrong layout there would surface as a confusing
 * native error. This test pins the layout the C++ `build_schema` walks.
 */
class SchemaDescriptorTest {

    /** A field with no index gets [IndexKind.NONE] and zeroed param slots. */
    @Test fun scalarFieldEncodesAsNoIndex() {
        val d = SchemaDescriptor.encode(
            CollectionSchema("c", listOf(FieldSchema("size", FieldType.INT64))),
            CollectionOptions(),
        )
        assertEquals(1, d.fieldCount)
        assertEquals("size", d.fieldNames[0])
        assertEquals(5, d.fieldDataTypes[0]) // INT64 -> C value 5
        assertEquals(false, d.fieldNullable[0])
        assertEquals(0, d.fieldDimensions[0])
        assertEquals(SchemaDescriptor.IndexKind.NONE, d.fieldIndexTypes[0])
        // Zeroed param slots — the JNI layer ignores them for NONE.
        assertEquals(0, d.indexM[0])
        assertEquals(0, d.indexMetric[0])
    }

    /** HNSW params land in the right per-field slots; metric maps to COSINE=3. */
    @Test fun hnswVectorFieldEncodesScalarsIntoItsSlot() {
        val d = SchemaDescriptor.encode(
            CollectionSchema(
                "c",
                listOf(
                    FieldSchema("name", FieldType.STRING),
                    FieldSchema(
                        "vec", FieldType.VECTOR_FP32, dimension = 8,
                        indexParams = IndexParams.HnswParams(m = 16, efConstruction = 200),
                    ),
                ),
            ),
            CollectionOptions(),
        )
        assertEquals(2, d.fieldCount)
        // Field 0: scalar, no index.
        assertEquals(SchemaDescriptor.IndexKind.NONE, d.fieldIndexTypes[0])
        // Field 1: HNSW vector.
        assertEquals(SchemaDescriptor.IndexKind.HNSW, d.fieldIndexTypes[1])
        assertEquals(8, d.fieldDimensions[1])
        assertEquals(23, d.fieldDataTypes[1]) // VECTOR_FP32 -> C value 23
        assertEquals(16, d.indexM[1])
        assertEquals(200, d.indexEfConstruction[1])
        assertEquals(3, d.indexMetric[1]) // COSINE -> 3
        // Field 0's HNSW slots stay zeroed (never read by the JNI layer).
        assertEquals(0, d.indexM[0])
    }

    /**
     * FTS filters pack flat across multiple FTS fields: ftsFilterNames holds all
     * filters concatenated, and ftsFilterFieldIndices[i] is the count for field i.
     * The C++ build loop advances a cursor by that count per FTS field.
     */
    @Test fun ftsFiltersPackFlatWithPerFieldCounts() {
        val d = SchemaDescriptor.encode(
            CollectionSchema(
                "c",
                listOf(
                    FieldSchema(
                        "a", FieldType.STRING,
                        // Two filters on one field. (The v0.5.1 factory only knows
                        // "lowercase"; the encoder is filter-agnostic — these are
                        // arbitrary strings proving the flat-pack layout.)
                        indexParams = IndexParams.FtsParams(filters = listOf("lowercase", "alpha")),
                    ),
                    FieldSchema("b", FieldType.STRING), // no index
                    FieldSchema(
                        "c", FieldType.STRING,
                        indexParams = IndexParams.FtsParams(filters = listOf("beta")),
                    ),
                ),
            ),
            CollectionOptions(),
        )
        assertEquals(3, d.fieldCount)
        // Field 0 has 2 filters, field 2 has 1; field 1 has none.
        assertEquals(2, d.ftsFilterFieldIndices[0])
        assertEquals(0, d.ftsFilterFieldIndices[1])
        assertEquals(1, d.ftsFilterFieldIndices[2])
        // Flat-packed, in field order.
        assertArrayEquals(arrayOf("lowercase", "alpha", "beta"), d.ftsFilterNames)
    }

    /** CollectionOptions map 1:1 onto the descriptor. */
    @Test fun optionsMapOneToOne() {
        val d = SchemaDescriptor.encode(
            CollectionSchema("c", listOf(FieldSchema("x", FieldType.STRING))),
            CollectionOptions(enableMmap = true, readOnly = true, maxBufferSize = 4096),
        )
        assertTrue(d.enableMmap)
        assertTrue(d.readOnly)
        assertEquals(4096L, d.maxBufferSize)
    }

    /** An empty field list encodes to a zero-count descriptor (valid edge case). */
    @Test fun emptyFieldListEncodesCleanly() {
        val d = SchemaDescriptor.encode(CollectionSchema("c", emptyList()), CollectionOptions())
        assertEquals(0, d.fieldCount)
        assertEquals(0, d.ftsFilterNames.size)
    }
}
