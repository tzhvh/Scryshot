package io.github.tzhvh.scryernext.zvec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable

/**
 * Issue 01: the handle-capability model (ADR 0005). Verifies the close-guard
 * contract on [OwnedHandle] and the **compile-time** guarantee that a
 * [BorrowedHandle] has no `close()` to mis-call.
 *
 * `NativeOwnedHandle` / `NativeBorrowedHandle` are internal, so this test lives
 * in the SDK's own package (the instrumentation test source set sees internal
 * members). The dangerous direction — closing a borrow — is verified by
 * *absence*: `BorrowedHandle::class` declares no `close()`, a one-line
 * reflection assertion. No public Phase-1 method returns a `BorrowedHandle`, so
 * there is no borrow to mis-close in the first place.
 */
@RunWith(AndroidJUnit4::class)
class ZvecHandleSafetyTest {

    /**
     * `close()` records the destroy via the injected callback; a second close is
     * a no-op. The volatile + synchronized double-check frees exactly once even
     * under concurrent calls.
     */
    @Test fun ownedCloseIsIdempotentAndDestroysOnce() {
        var destroyCalls = 0L
        var destroyedPtr = -1L
        val handle = NativeOwnedHandle(ptr = 0xDEADBEEF) { ptr ->
            destroyCalls++
            destroyedPtr = ptr
        }

        assertFalse("fresh handle is not closed", handle.isClosed)
        assertEquals(0xDEADBEEF, handle.ptr)

        handle.close()
        assertTrue("handle is closed after close()", handle.isClosed)
        assertEquals("destroyer fired exactly once", 1, destroyCalls)
        assertEquals("destroyer saw the raw ptr", 0xDEADBEEF, destroyedPtr)

        // Second close: synchronized double-check makes it a no-op.
        handle.close()
        assertEquals("second close must not re-destroy", 1, destroyCalls)
        assertTrue(handle.isClosed)
    }

    /**
     * `BorrowedHandle` deliberately has no `close()`. The dangerous direction —
     * closing a borrow, which would double-free in the parent — is a compile
     * error, not a runtime flag. We assert the method is genuinely absent, so a
     * future refactor that accidentally adds it fails loudly here.
     */
    @Test fun borrowedHandleHasNoCloseMethod() {
        val closeOnBorrowed = BorrowedHandle::class.java.declaredMethods
            .firstOrNull { it.name == "close" }
        assertEquals(
            "BorrowedHandle must NOT declare close() (closing a borrow double-frees); " +
                "the compile-time guarantee is the method set",
            null,
            closeOnBorrowed,
        )

        // The capability split: OwnedHandle extends Closeable, BorrowedHandle does not.
        assertTrue(
            "OwnedHandle extends Closeable (caller frees the resource)",
            Closeable::class.java.isAssignableFrom(OwnedHandle::class.java),
        )
        assertFalse(
            "BorrowedHandle does NOT extend Closeable",
            Closeable::class.java.isAssignableFrom(BorrowedHandle::class.java),
        )

        // A NativeBorrowedHandle wraps the ptr but cannot be closed.
        val borrow = NativeBorrowedHandle(0xCAFEBABE)
        assertEquals(0xCAFEBABE, borrow.ptr)
        assertFalse(Closeable::class.java.isAssignableFrom(borrow.javaClass))
    }
}
