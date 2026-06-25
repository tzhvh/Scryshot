package io.github.tzhvh.scryernext.ingestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [computeSyncPlan] — the pure, device-free set-difference at
 * the keystone of ADR 0004 §5's dedup-as-resumption (ADR 0004 §7.1).
 *
 * Two threads run through this file:
 *
 * 1. **Set-diff semantics** — empty, disjoint, overlapping, stale-only. These
 *    pin the three output sets against trivial inputs.
 * 2. **Identity-agnosticism** — the load-bearing thread. `computeSyncPlan`
 *    takes two opaque `Set<String>` and must produce correct results *regardless
 *    of what those strings mean*. The `...survives_the_room_to_zvec_transition`
 *    test below is executable documentation of the contract that lets this
 *    function pass through the Room→zvec storage transition unchanged: under
 *    Room the caller hands in content URIs; under zvec it hands in content
 *    hashes. The function neither knows nor cares. A reader who knows nothing
 *    about zvec should understand from that test *why* the function must stay
 *    agnostic.
 *
 * Plain JUnit4 (`org.junit.Assert.*`) — matches the repo convention; no kotest,
 * no Robolectric. This is a pure JVM test with zero Android/coroutine coupling.
 */
class ComputeSyncPlanTest {

    // --------------------------------------------------------------------------
    // 1. Set-diff semantics
    // --------------------------------------------------------------------------

    @Test
    fun both_empty_yields_three_empty_sets() {
        val plan = computeSyncPlan(liveKeys = emptySet(), dbKeys = emptySet())

        assertTrue(plan.new.isEmpty())
        assertTrue(plan.alreadyIndexed.isEmpty())
        assertTrue(plan.stale.isEmpty())
    }

    @Test
    fun fully_overlapping_yields_all_already_indexed() {
        val keys = setOf("a", "b", "c")

        val plan = computeSyncPlan(liveKeys = keys, dbKeys = keys)

        assertEquals(keys, plan.alreadyIndexed)
        assertTrue(plan.new.isEmpty())
        assertTrue(plan.stale.isEmpty())
    }

    @Test
    fun fully_disjoint_yields_new_equals_live_and_stale_equals_db() {
        val live = setOf("a", "b")
        val db = setOf("c", "d")

        val plan = computeSyncPlan(liveKeys = live, dbKeys = db)

        assertEquals(live, plan.new)
        assertEquals(db, plan.stale)
        assertTrue(plan.alreadyIndexed.isEmpty())
    }

    @Test
    fun stale_only_when_live_empty_but_db_non_empty() {
        val db = setOf("x", "y", "z")

        val plan = computeSyncPlan(liveKeys = emptySet(), dbKeys = db)

        assertEquals(db, plan.stale)
        assertTrue(plan.new.isEmpty())
        assertTrue(plan.alreadyIndexed.isEmpty())
    }

    @Test
    fun new_only_when_live_non_empty_but_db_empty() {
        val live = setOf("p", "q")

        val plan = computeSyncPlan(liveKeys = live, dbKeys = emptySet())

        assertEquals(live, plan.new)
        assertTrue(plan.alreadyIndexed.isEmpty())
        assertTrue(plan.stale.isEmpty())
    }

    @Test
    fun partially_overlapping_partitions_all_three_ways() {
        // live ∩ db = {b}; live \ db = {a}; db \ live = {c}
        val live = setOf("a", "b")
        val db = setOf("b", "c")

        val plan = computeSyncPlan(liveKeys = live, dbKeys = db)

        assertEquals(setOf("a"), plan.new)
        assertEquals(setOf("b"), plan.alreadyIndexed)
        assertEquals(setOf("c"), plan.stale)
    }

    @Test
    fun the_three_output_sets_partition_the_union_of_inputs() {
        // Invariant every caller relies on: no key is lost, no key is doubled,
        // and alreadyIndexed/new/stale are mutually disjoint. This is what makes
        // dedup-as-resumption (§5) sound: "completed candidates leave the
        // unindexed set" only holds if the partition is exact.
        val live = setOf("a", "b", "c", "d")
        val db = setOf("b", "d", "e", "f")

        val plan = computeSyncPlan(liveKeys = live, dbKeys = db)

        val union = plan.new + plan.alreadyIndexed + plan.stale
        assertEquals(live + db, union)
        // Mutual disjointness: any two of the three share no element.
        assertTrue((plan.new intersect plan.alreadyIndexed).isEmpty())
        assertTrue((plan.new intersect plan.stale).isEmpty())
        assertTrue((plan.alreadyIndexed intersect plan.stale).isEmpty())
    }

    // --------------------------------------------------------------------------
    // 2. Identity-agnosticism — the load-bearing thread
    // --------------------------------------------------------------------------

    @Test
    fun within_room_era_keys_are_content_uris() {
        // Baseline: under Room the caller translates each screenshot's identity
        // to its content URI (Room's unique `uri` index). `computeSyncPlan`
        // treats them as opaque strings. This is the "easy" case — same key
        // space on both sides — and exists to contrast with the cross-key-space
        // test below.
        val onDisk = setOf(
            "content://media/external/images/42",
            "content://media/external/images/43"
        )
        val indexed = setOf(
            "content://media/external/images/42"   // already done
        )

        val plan = computeSyncPlan(liveKeys = onDisk, dbKeys = indexed)

        assertEquals(setOf("content://media/external/images/43"), plan.new)
        assertEquals(setOf("content://media/external/images/42"), plan.alreadyIndexed)
        assertTrue(plan.stale.isEmpty())
    }

    @Test
    fun survives_the_room_to_zvec_transition_because_it_is_identity_agnostic() {
        // ── ROADMAP COUPLING: ADR 0004 §7.1 table ────────────────────────────
        //
        // This test is the executable proof that `computeSyncPlan` needs NO
        // change when storage swaps from Room to zvec (roadmap Phase 2). The
        // function is identity-model-independent by construction: the CALLER
        // translates its identity model into two opaque `Set<String>` before
        // calling, so swapping the identity model (content URI → content hash)
        // changes only what the caller feeds in, never the function.
        //
        // Here we feed it the zvec-era key space directly: live keys are
        // content hashes (`sha256:…`), db keys are content hashes. The function
        // computes the identical three-set partition it would for URIs, because
        // it never inspects the strings' meaning. If a future edit makes this
        // function care whether a key `startsWith("content://")` or
        // `startsWith("sha256:")`, this test fails — and it should, because
        // such coupling would force a rewrite at the zvec transition.
        //
        // Contrast with `within_room_era_keys_are_content_uris`: same function,
        // same logic, different opaque payload. That is the whole contract.
        val onDiskHashes = setOf(
            "sha256:1111111111111111111111111111111111111111111111111111111111aaaa",
            "sha256:2222222222222222222222222222222222222222222222222222222222bbbb",
            "sha256:3333333333333333333333333333333333333333333333333333333333cccc"
        )
        val indexedHashes = setOf(
            "sha256:1111111111111111111111111111111111111111111111111111111111aaaa", // done
            "sha256:4444444444444444444444444444444444444444444444444444444444dddd"  // stale: db has it, disk doesn't
        )

        val plan = computeSyncPlan(liveKeys = onDiskHashes, dbKeys = indexedHashes)

        assertEquals(
            setOf(
                "sha256:2222222222222222222222222222222222222222222222222222222222bbbb",
                "sha256:3333333333333333333333333333333333333333333333333333333333cccc"
            ),
            plan.new
        )
        assertEquals(
            setOf("sha256:1111111111111111111111111111111111111111111111111111111111aaaa"),
            plan.alreadyIndexed
        )
        assertEquals(
            setOf("sha256:4444444444444444444444444444444444444444444444444444444444dddd"),
            plan.stale
        )
    }

    @Test
    fun does_not_inspect_or_act_on_stale_keys() {
        // Forward-looking seam: under Room the caller DELETES stale rows; under
        // zvec it NULLs the locator and KEEPS the doc (ADR 0004 §7.1 table).
        // `computeSyncPlan` must hand back the raw stale set and do neither —
        // baking either action in would couple it to one identity model. This
        // test pins that the function only *reports* stale keys; the disposition
        // is the caller's. (No assertion on disposition is possible here because
        // there is none — that absence is exactly what we are protecting.)
        val staleRow = "content://media/external/images/999"

        val plan = computeSyncPlan(liveKeys = emptySet(), dbKeys = setOf(staleRow))

        assertEquals(setOf(staleRow), plan.stale)
        // The function returns a plain data class; there is no callback, side
        // channel, or repository reference through which it could act on stale.
        // Its return type carries only the three inert sets.
    }
}
