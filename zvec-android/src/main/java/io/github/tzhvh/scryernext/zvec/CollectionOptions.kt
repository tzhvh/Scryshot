package io.github.tzhvh.scryernext.zvec

/**
 * How a collection is opened/created. Maps 1:1 to zvec's
 * `zvec_collection_options_t` (`c_api.h:2386-2466`); the SDK builds the C handle
 * internally, applies the three setters, passes it to
 * `create_and_open`/`open`, and frees it via `OptionsGuard`. Pure data —
 * **not** [Closeable]: the caller has nothing to free.
 *
 * **`enableMmap = true` is the default but is an open verification debt (R8).**
 * The roadmap flags mmap as "API-level-dependent on Android" without specifying
 * levels; this doc does **not** assert `true` is safe on `minSdk = 29`. The
 * default matches zvec's own and is the performance-correct choice; before Phase
 * 2 ships, run one create/insert/query cycle with `enableMmap = true` on a real
 * minSdk-29 device, and flip this default to `false` (or make it API-level-
 * conditional in `androidDefaults`) if it misbehaves. Documenting it as "verified
 * safe" without that run would be the sin the rest of the design corrects.
 *
 * @param enableMmap memory-map the collection files.
 * @param readOnly open read-only (a second-open / preview path).
 * @param maxBufferSize `0` = zvec's engine default; a write-amplification knob.
 */
data class CollectionOptions(
    val enableMmap: Boolean = true,
    val readOnly: Boolean = false,
    val maxBufferSize: Long = 0,
)
