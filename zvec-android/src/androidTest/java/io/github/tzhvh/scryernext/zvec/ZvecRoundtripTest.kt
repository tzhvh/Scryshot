package io.github.tzhvh.scryernext.zvec

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 0 exit gate (ZVEC_PHASE0.md §"Verification plan", Layer 2).
 *
 * Self-contained instrumentation test — no app module needed. Runs as
 * `:zvec-android:connectedDebugAndroidTest` on an x86_64 emulator.
 */
@RunWith(AndroidJUnit4::class)
class ZvecRoundtripTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Closes the ZVEC_VERSION ↔ submodule SHA ↔ compiled-.so consistency loop (Q10/Q11).
     *
     * zvec's version string (c_api.cc zvec_get_version) is the raw output of
     * `git describe --tags`, baked in at compile time: exactly `"v0.5.1"` at the tag,
     * becoming `"v0.5.1-N-g<sha>"` the moment HEAD moves past it. So exact equality
     * against BuildConfig.ZVEC_VERSION is the right check: any drift appends a
     * suffix and fails loudly here, rather than shipping a .so built from an
     * unreleased commit. (A previous `startsWith` assertion masked exactly that drift.)
     */
    @Test fun versionMatchesPinnedTag() {
        val native = ZvecNative.nativeGetVersion()
        assertEquals(
            "native version drift: .so not built from the tag recorded in ZVEC_VERSION",
            BuildConfig.ZVEC_VERSION,
            native
        )
    }

    /**
     * End-to-end vertical slice: create -> insert (both NON-nullable fields) -> close.
     *
     * The probe schema (nativeCreateAndOpen) declares `title` and `content` as
     * nullable=false, so a valid doc MUST supply both. This intentionally exercises
     * the engine's required-field validation path — the whole point of the second
     * field (Q8). nativeCreateAndOpen requires the target path to NOT pre-exist
     * (it errors if it does), so we do not mkdir the cache subdir.
     */
    @Test fun createInsertCloseRoundtrips() {
        val dir = File(ctx.cacheDir, "zvec_test_${System.nanoTime()}")
        val h = ZvecNative.nativeCreateAndOpen(dir.absolutePath, "screenshots")
        assertTrue("handle must be non-zero", h != 0L)
        assertEquals(1, ZvecNative.nativeInsertString(h, "deadbeef", "My Title", "hello ocr"))
        ZvecNative.nativeClose(h)
    }
}
