/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * The work plan [computeSyncPlan] derives before the [IngestionEngine] runs
 * (ADR 0004 §7.1). Three disjoint sets partitioning the universe of keys the
 * caller handed in:
 *
 * - [new] — on disk, not yet indexed → queue for OCR.
 * - [alreadyIndexed] — present in both → skip (the early-progress emit of
 *   §7.4 counts these so the UI updates before any OCR runs).
 * - [stale] — in the DB, no longer on disk → caller decides the action.
 *
 * NOTE: [computeSyncPlan] deliberately returns [stale] and does *nothing* with
 * it. The stale action is the caller's concern and diverges at the Room→zvec
 * transition (ADR 0004 §7.1 table): under Room it is "delete the row"; under
 * zvec it is "null the `locator` field, keep the doc" (the content-hash + OCR
 * remain valid for re-matching if the file resurfaces). Baking either action
 * into this pure function would couple it to one identity model and destroy the
 * property the cross-key-space test exists to protect.
 *
 * See: [ADR 0004 §7.1, §5](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
data class SyncPlan(
    val new: Set<String>,
    val alreadyIndexed: Set<String>,
    val stale: Set<String>
)

/**
 * Pure, side-effect-free set-difference over two opaque key spaces (ADR 0004 §7.1).
 *
 * Identity-model-independent: the caller has already translated its identity
 * model (locator/URI under Room; `content_hash` under zvec) into two opaque
 * [Set]s. This function never resolves identity itself — that is the whole
 * point, and the reason it survives the Room→zvec transition *unchanged*. What
 * changes at the transition is the *caller's* key gathering and stale handling
 * (see [SyncPlan]); not this function.
 *
 * This is the keystone of dedup-as-resumption (ADR 0004 §5): completed
 * candidates leave the unindexed set, so the next run naturally picks up the
 * remainder. No worklist checkpoint is maintained — the set-diff *is* the
 * checkpoint, and its purity is what makes that safe to reason about.
 *
 * @param liveKeys what is on disk now (the "live" key space).
 * @param dbKeys   what the DB already holds (the "indexed" key space).
 * @return the three disjoint sets; see [SyncPlan].
 */
fun computeSyncPlan(liveKeys: Set<String>, dbKeys: Set<String>): SyncPlan = SyncPlan(
    new = liveKeys - dbKeys,
    alreadyIndexed = liveKeys intersect dbKeys,
    stale = dbKeys - liveKeys
)
