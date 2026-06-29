package io.github.tzhvh.scryernext.zvec

/**
 * Process-wide SDK entry point: initialize zvec once, then every later SDK call
 * is made against the initialized library.
 *
 * **Lifecycle:** call [init] from `Application.onCreate` with
 * [ZvecConfig.androidDefaults]. The C `zvec_initialize` is `std::call_once`
 * internally (a second call returns `ALREADY_EXISTS`), so once-per-process holds
 * regardless of call site. There is **no `shutdown()`** — `Application.onTerminate`
 * is unreliable on Android, and calling `zvec_shutdown` breaks later tests in the
 * same process. The process owns the library for its lifetime.
 *
 * **Tests:** focused instrumentation tests can skip the `Application` dance —
 * the SDK's internal [ensureInitialized] lazily initializes zvec with cgroup-
 * derived defaults on first use. Documented hazard: first call wins; config set
 * in test A persists for test B in the same process.
 */
object Zvec {
    init {
        // Force the .so load + JNI_OnLoad so that nativeInitialize/nativeIsInitialized
        // are resolvable even if Zvec is the first SDK surface touched.
        ZvecNative
    }

    /**
     * Initialize zvec with [config]. Once-per-process: a call after the library
     * is already initialized is a no-op that returns `false` (it surfaces the C
     * `ALREADY_EXISTS`, caught internally — see `## Why a no-op` below).
     *
     * Pass `null` to use zvec's cgroup-derived defaults (the lazy-fallback path
     * does this internally).
     *
     * @return true if this call initialized the library; false if it was already
     *   initialized (first call wins).
     */
    fun init(config: ZvecConfig?): Boolean {
        if (isInitialized) return false
        val code = nativeInitializeCode(config)
        if (code == ZvecErrorCode.OK) {
            return true
        }
        // ALREADY_EXISTS is the documented second-call outcome — the once-per-process
        // guarantee means a concurrent/second caller simply lost the race. Every other
        // code is a real init failure (INVALID_ARGUMENT from a bad config, etc.).
        if (code == ZvecErrorCode.ALREADY_EXISTS) {
            return false
        }
        // Enrich the bare code with zvec's last-error detail so a config/init failure
        // is debuggable (the code alone, e.g. INTERNAL_ERROR, is opaque).
        throw ZvecException(code, ZvecNative.nativeGetLastError())
    }

    /** True once zvec has been initialized in this process. */
    val isInitialized: Boolean
        get() = ZvecNative.nativeIsInitialized()

    /**
     * Lazily initialize zvec with cgroup-derived defaults if [init] was never
     * called. Lets focused instrumentation tests skip the `Application` dance.
     * Internal: callers within the SDK gate collection/handle entry points here.
     */
    internal fun ensureInitialized() {
        if (isInitialized) return
        // NULL config — zvec's own defaults (memory/thread pools derived from the
        // cgroup, no log sink). Mirrors the documented lazy-fallback contract.
        val code = nativeInitializeCode(config = null)
        if (code != ZvecErrorCode.OK && code != ZvecErrorCode.ALREADY_EXISTS) {
            throw ZvecException(code, ZvecNative.nativeGetLastError())
        }
    }

    /**
     * Translates the typed [ZvecConfig] (or null) into the JNI call and maps the
     * raw C int back to [ZvecErrorCode]. Kept here so the JNI boundary stays
     * "(primitive) -> Int" and the typed construction lives in Kotlin.
     */
    private fun nativeInitializeCode(config: ZvecConfig?): ZvecErrorCode {
        val fileLog = (config?.logConfig as? ZvecLogConfig.file)
        val raw = ZvecNative.nativeInitialize(
            nullConfig = config == null,
            memoryLimitBytes = config?.memoryLimitBytes ?: 0L,
            queryThreadCount = config?.queryThreadCount ?: 0,
            optimizeThreadCount = config?.optimizeThreadCount ?: 0,
            nullLogConfig = fileLog == null,
            logLevel = fileLog?.level?.toNative() ?: 0,
            logDir = fileLog?.dir?.absolutePath ?: "",
            logBasename = fileLog?.basename ?: "",
            logSizeMb = (fileLog?.sizeMb ?: 0u).toLong(),
            logOverdueDays = (fileLog?.overdueDays ?: 0u).toLong(),
        )
        return ZvecErrorCode.fromInt(raw)
    }
}
