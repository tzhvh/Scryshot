package io.github.tzhvh.scryernext.zvec

import java.io.Closeable

/**
 * A handle over an opaque zvec C object, wrapped as a `jlong`. ADR 0005.
 *
 * The capability split below encodes "can I close this?" as a *type* property,
 * not a runtime flag — closing a borrowed handle would double-free in the
 * parent, so that direction is a compile error, not a runtime crash.
 *
 * Every owned handle is `Application`- or `use {}`-scoped. No
 * `java.lang.ref.Cleaner`, no `finalize()`: minSdk 29 can't use `Cleaner` (API
 * 33+) cleanly, and the explicit `Closeable` already provides cleanup
 * (Phase-0 Q6, confirmed here).
 */
interface ZvecHandle {
    /** The raw `jlong`-wrapped C pointer. */
    val ptr: Long
}

/**
 * A handle the caller owns and must [close]. The dangerous lifetime bug —
 * touching the handle after close — is guarded by [isClosed].
 */
interface OwnedHandle : ZvecHandle, Closeable {
    val isClosed: Boolean
}

/**
 * A handle borrowed from a parent. Deliberately has **no [close]**: closing a
 * borrow would double-free in the parent, so the dangerous direction does not
 * compile. `BorrowedHandle::class` declaring no `close()` is itself the safety
 * guarantee (asserted in `ZvecHandleSafetyTest`).
 */
interface BorrowedHandle : ZvecHandle

/**
 * Owned handle over a native object, freed by [destroyer] on [close].
 *
 * `close()` is idempotent: the volatile [closed] flag is double-checked inside a
 * `synchronized(this)` block, so concurrent `close()` calls free exactly once.
 * Operations after close are the caller's bug — surfaced as the handle having
 * `isClosed == true` (the public methods that take an [OwnedHandle] check this
 * and throw; the JNI layer's own null-handle guard is the backstop).
 */
internal class NativeOwnedHandle(
    override val ptr: Long,
    private val destroyer: (Long) -> Unit,
) : OwnedHandle {
    @Volatile private var closed = false
    override val isClosed: Boolean get() = closed

    override fun close() {
        if (!closed) {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    destroyer(ptr)
                }
            }
        }
    }
}

/** A borrow over a native object — never freed here (the parent owns it). */
internal class NativeBorrowedHandle(override val ptr: Long) : BorrowedHandle
