package io.github.tzhvh.scryernext.zvec

/**
 * Typed mirror of zvec's `zvec_error_code_t` (`c_api.h:108`).
 *
 * Callers write exhaustive `when (e.code)` blocks against this enum instead of
 * unchecked integer switches. `fromInt` falls back to [UNKNOWN] so a forward-
 * compatible new C code value degrades gracefully rather than throwing on the
 * exception path. The [OK] entry exists only for completeness of the C-API
 * mapping — [ZvecException] is never constructed with it (a zero code is
 * success, not an exception).
 */
enum class ZvecErrorCode(val value: Int) {
    OK(0),
    NOT_FOUND(1),
    ALREADY_EXISTS(2),
    INVALID_ARGUMENT(3),
    PERMISSION_DENIED(4),
    FAILED_PRECONDITION(5),
    RESOURCE_EXHAUSTED(6),
    UNAVAILABLE(7),
    INTERNAL_ERROR(8),
    NOT_SUPPORTED(9),
    UNKNOWN(10);

    companion object {
        fun fromInt(v: Int): ZvecErrorCode =
            entries.firstOrNull { it.value == v } ?: UNKNOWN
    }
}

/**
 * The one exception type every later Phase-1 issue throws.
 *
 * [code] is the typed [ZvecErrorCode]; [detail] is the raw `zvec_get_last_error`
 * message (nullable: zvec does not always set one). Both are exposed as typed
 * fields so callers stop string-parsing [RuntimeException.message].
 *
 * The `(Int, String?)` constructor is the JNI bridge: the C layer still passes a
 * raw int code, and [ZvecErrorCode.fromInt] does the promotion. It is `internal`
 * (R8 strips internal members), so the ProGuard keep rule must name it
 * explicitly — see `consumer-rules.pro`.
 */
class ZvecException(
    val code: ZvecErrorCode,
    val detail: String? = null,
) : RuntimeException(
    "zvec " + code.name.lowercase() + (detail?.let { ": $it" } ?: "")
) {
    /** Bridge for JNI: the C layer still passes a raw int code. */
    internal constructor(code: Int, detail: String?) : this(ZvecErrorCode.fromInt(code), detail)
}
