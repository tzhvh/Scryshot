package io.github.tzhvh.scryernext.zvec

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the integer values the Kotlin schema layer hands across JNI to zvec's C
 * enums (`zvec_data_type_t`, `zvec_metric_type_t`, `zvec_index_type_t`,
 * `c_api.h`).
 *
 * These maps use explicit integers (not `ordinal()`) precisely because a C-side
 * renumber is a **silent runtime mismatch, not a compile error** — the JNI
 * marshalling would pass the wrong int and the engine would misread the field or
 * index type. This test is the JVM-visible half of that guard: a zvec bump that
 * renumbers any of these values fails loudly here, on the JVM, before reaching a
 * device.
 *
 * Values are pinned against the v0.7.0 header (`c_api.h` at tag `v0.7.0`):
 * - `ZVEC_DATA_TYPE_*` — `c_api.h:838-866`
 * - `ZVEC_INDEX_TYPE_*` — `c_api.h:875-884`
 * - `ZVEC_METRIC_TYPE_*` — `c_api.h:894-898`
 *
 * The v0.6.0 bump (issue 01) added `ZVEC_INDEX_TYPE_HNSW_RABITQ 4` and
 * `ZVEC_INDEX_TYPE_DISKANN 5` but **renumbered nothing** this app pins — this
 * test recorded "no drift" and now guards against the next bump. The v0.7.0
 * bump (issue 01 retarget) likewise added `ZVEC_INDEX_TYPE_VAMANA 6` and
 * `ZVEC_INDEX_TYPE_IVF_RABITQ 7` — again renumbering nothing pinned; "no
 * drift" recorded 2026-09-20. The cpp half of the index-type guard is symbolic
 * (`INDEX_KIND_*` → `ZVEC_INDEX_TYPE_*` in `zvec_jni_marshalling.h`), so the
 * [SchemaDescriptor.IndexKind] ints here are the only place a stale mirror
 * could hide on the JVM side.
 */
class SchemaEnumMappingTest {

    /** `FieldType.toNative()` mirrors `ZVEC_DATA_TYPE_*` (`c_api.h:838-866`). */
    @Test fun fieldTypeMapsToCDataTypeValues() {
        assertEquals(2, FieldType.STRING.toNative())
        assertEquals(3, FieldType.BOOL.toNative())
        assertEquals(4, FieldType.INT32.toNative())
        assertEquals(5, FieldType.INT64.toNative())
        assertEquals(6, FieldType.UINT32.toNative())
        assertEquals(7, FieldType.UINT64.toNative())
        assertEquals(8, FieldType.FLOAT.toNative())
        assertEquals(9, FieldType.DOUBLE.toNative())
        assertEquals(22, FieldType.VECTOR_FP16.toNative())
        assertEquals(23, FieldType.VECTOR_FP32.toNative())
        assertEquals(26, FieldType.VECTOR_INT8.toNative())
    }

    /** `MetricType.toNative()` mirrors `ZVEC_METRIC_TYPE_*` (`c_api.h:894-898`). */
    @Test fun metricTypeMapsToCMetricTypeValues() {
        assertEquals(1, MetricType.L2.toNative())
        assertEquals(2, MetricType.IP.toNative())
        assertEquals(3, MetricType.COSINE.toNative())
    }

    /**
     * [SchemaDescriptor.IndexKind] mirrors `ZVEC_INDEX_TYPE_*`
     * (`c_api.h:875-884`). Only the index kinds the SDK exposes are pinned;
     * `HNSW_RABITQ` (4), `DISKANN` (5), `VAMANA` (6) and `IVF_RABITQ` (7)
     * exist in the C header but have no Kotlin arm yet.
     */
    @Test fun indexKindMirrorsCIndexTypeValues() {
        assertEquals(0, SchemaDescriptor.IndexKind.NONE)
        assertEquals(1, SchemaDescriptor.IndexKind.HNSW)
        assertEquals(2, SchemaDescriptor.IndexKind.IVF)
        assertEquals(3, SchemaDescriptor.IndexKind.FLAT)
        assertEquals(10, SchemaDescriptor.IndexKind.INVERT)
        assertEquals(11, SchemaDescriptor.IndexKind.FTS)
    }
}
