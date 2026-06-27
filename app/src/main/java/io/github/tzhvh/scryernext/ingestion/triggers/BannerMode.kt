/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.

 */

package io.github.tzhvh.scryernext.ingestion.triggers

/**
 * The two visual states of the in-app ingestion banner (issue `18`) plus its hidden state.
 *
 * - [IDLE_BACKLOG] (Mode A) — the nudge: "you have N unindexed screenshots" + Index now + Snooze.
 *   Visible iff `!pending && backlog > threshold && !snoozed`.
 * - [ACTIVE] (Mode B) — the status: indexing progress + Stop. Visible iff `pending`.
 * - [HIDDEN] — neither condition holds (no session, backlog at/below threshold, or snoozed).
 *
 * The two modes have **different dismissal semantics** (a shared dismiss is a footgun — dismissing
 * the active session implies stopping the job, but doesn't), so they are distinct values, not one
 * boolean. See [`PHASE3_DESIGN_DECISIONS.md`](file:///.scratch/ingestion/PHASE3_DESIGN_DECISIONS.md) §5.
 */
enum class BannerMode { HIDDEN, IDLE_BACKLOG, ACTIVE }

/**
 * The banner's *mode* decision as a pure, device-free function (issue 18 AC: the visibility
 * decision "is extractable as a pure, unit-testable function, symmetric with issues 12/13/14's
 * pure-helper convention"). Extracted so the rule — active session wins; else backlog past
 * threshold; else hidden — has one named, documented, tested home.
 *
 * **Deviation from the issue's literal signature** `shouldShow(progress, pending, backlog, threshold)`:
 * `progress` is redundant with `pending` for the *mode* decision. `pending` is the union of
 * `progress is Indexing || WorkInfo state ∈ {RUNNING, ENQUEUED}` (see [isPending]); passing both
 * is duplicate information. The `Progress.Indexing.current/total` numerics are read separately at
 * render time (they shape Mode B's *contents*, not its *visibility*). Dropping the `progress`
 * parameter removes a redundant input; the rule is otherwise the issue's intent.
 *
 * @param pending    whether an ingestion session is pending (the [IngestionSession.isSessionPending]
 *                   union — in-memory `Indexing` OR WM `RUNNING`/`ENQUEUED`).
 * @param backlog    the current unindexed count ([IngestionProgressStore.backlog]).
 * @param threshold  the backlog threshold above which Mode A nudge appears
 *                   ([IngestionConfig.BACKLOG_THRESHOLD] — shared with issues 11/12/13).
 * @param snoozed    whether the user snoozed Mode A this process (in-memory only; see issue 18).
 *                   Snooze suppresses the nudge but never the active-session status.
 */
internal fun bannerMode(
    pending: Boolean,
    backlog: Int,
    threshold: Int,
    snoozed: Boolean = false
): BannerMode = when {
    pending -> BannerMode.ACTIVE
    backlog > threshold && !snoozed -> BannerMode.IDLE_BACKLOG
    else -> BannerMode.HIDDEN
}
