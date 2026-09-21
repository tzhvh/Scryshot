package io.github.tzhvh.scryernext.zvec

import java.io.File

/**
 * Typed mirror of zvec's `zvec_log_level_t` (`c_api.h:413`).
 *
 * Order matches the C enum values (DEBUG=0 … FATAL=4); the JNI layer maps these
 * to the C integer when building a `zvec_log_config_t`.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL;

    /** C-side `zvec_log_level_t` ordinal; the JNI layer consumes this. */
    internal fun toNative(): Int = ZVEC_LOG_LEVELS[this] ?: ZVEC_LOG_LEVEL_INFO

    private companion object {
        // Explicit map (not `ordinal`) so a reorder here can never silently drift
        // the value handed to zvec — the C ordinals are a stability surface.
        val ZVEC_LOG_LEVELS = mapOf(
            DEBUG to 0,
            INFO to 1,
            WARN to 2,
            ERROR to 3,
            FATAL to 4,
        )
        const val ZVEC_LOG_LEVEL_INFO = 1
    }
}

/**
 * How zvec writes logs. Phase 1 ships exactly one arm: the file sink.
 *
 * A `logcat(level)` arm is deferred — verified against `c_api.h` that the
 * `zvec_log_config_t` type has only `create_console` / `create_file` factory
 * constructors and **no callback/writer/user_data parameter**, so there is no
 * stable C-API hook to install a logcat sink (the stdout→logcat pipe shim would
 * be a purely-additive `JNI_OnLoad` change for a later phase). The console sink
 * is deliberately not exposed: Android discards stdout for non-debuggable apps.
 *
 * Additive later: a `logcat` arm does not break existing callers.
 */
sealed interface ZvecLogConfig {
    /**
     * File sink: rotating files under [dir], named `<basename>…`, capped at
     * [sizeMb] MB each, pruned after [overdueDays] days. Mirrors
     * `zvec_config_log_create_file` (`c_api.h:471`).
     *
     * [sizeMb] must be **> 128**: zvec rejects anything `<= MIN_LOG_FILE_SIZE`
     * (`config.h:26`, `config.cc:103`) with INTERNAL_ERROR. The default 200 MB
     * clears that floor with headroom. [overdueDays] must be `> 0`.
     */
    data class file(
        val level: LogLevel,
        val dir: File,
        val basename: String = "zvec",
        val sizeMb: UInt = 200u,
        val overdueDays: UInt = 7u,
    ) : ZvecLogConfig
}

/**
 * Process-wide init configuration. Set once via [Zvec.init] from
 * `Application.onCreate`; the SDK never shuts the library down.
 *
 * Surface is the three things a real app needs: a memory cap, the two native
 * thread-pool sizes (zvec's *internal* pools — independent of the JVM dispatcher,
 * see ADR 0007), and an optional file log sink. The three scan-ratio knobs
 * (`invert_to_forward_scan_ratio` etc.) are deferred to Phase 5: speculative
 * tuning with no measured caller.
 *
 * Use [androidDefaults] for the canonical production config.
 *
 * @param memoryLimitBytes null leaves zvec's cgroup-derived default.
 * @param queryThreadCount zvec's native query thread pool size.
 * @param optimizeThreadCount zvec's native optimize thread pool size.
 */
data class ZvecConfig(
    val memoryLimitBytes: Long?,
    val queryThreadCount: Int,
    val optimizeThreadCount: Int,
    val logConfig: ZvecLogConfig? = null,
) {
    companion object {
        /**
         * The canonical Android config: 512 MB cap, a 2-thread query pool, a
         * 1-thread optimize pool, and a file sink under `<filesDir>/logs/zvec`.
         * The log level is [LogLevel.DEBUG] for debuggable builds, [LogLevel.INFO]
         * otherwise — matching the debug/release decision that needs `BuildConfig`.
         */
        fun androidDefaults(filesDir: File, debug: Boolean): ZvecConfig = ZvecConfig(
            memoryLimitBytes = 512L * 1024 * 1024,
            queryThreadCount = 2,
            optimizeThreadCount = 1,
            logConfig = ZvecLogConfig.file(
                level = if (debug) LogLevel.DEBUG else LogLevel.INFO,
                dir = File(filesDir, "logs"),
                basename = "zvec",
            ),
        )
    }
}
