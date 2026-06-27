/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.

 */

package io.github.tzhvh.scryernext.ingestion.triggers

/**
 * The visual states of the in-app ingestion banner (issue `18`) plus its hidden state.
 *
 * - [IDLE_BACKLOG] (Mode A) — the nudge: "you have N unindexed screenshots" + Index now + Snooze.
 *   Visible iff `!pending && backlog > threshold && !snoozed`.
 * - [ACTIVE] (Mode B) — the status: indexing progress + Stop. Visible iff `pending`.
 * - [SUCCESS] (Mode B-terminal) — transient "all done ✓" shown for ~4s after a run completes
 *   (issue: banner-revert-on-completion bug). The "done" claim is only true at the instant the
 *   job finished — every second after, new files could land — so SUCCESS is deliberately
 *   short-lived; after the dwell the banner re-evaluates against the live `backlog` (HIDDEN if
 *   empty, IDLE_BACKLOG with the new count if not). See [PHASE3_DESIGN_DECISIONS.md] §success.
 * - [HIDDEN] — neither condition holds (no session, backlog at/below threshold, or snoozed).
 *
 * The modes have **different dismissal semantics** (a shared dismiss is a footgun — dismissing
 * the active session implies stopping the job, but doesn't), so they are distinct values, not
 * booleans. See [`PHASE3_DESIGN_DECISIONS.md`](file:///.scratch/ingestion/PHASE3_DESIGN_DECISIONS.md) §5.
 *
 * Precedence (highest first): SUCCESS > ACTIVE > IDLE_BACKLOG > HIDDEN. A pending session that
 * completes flips ACTIVE→SUCCESS for the dwell, never ACTIVE→IDLE_BACKLOG (the stale-nudge bug).
 */
enum class BannerMode { HIDDEN, IDLE_BACKLOG, ACTIVE, SUCCESS }

/**
 * The banner's *mode* decision as a pure, device-free function (issue 18 AC: the visibility
 * decision "is extractable as a pure, unit-testable function, symmetric with issues 12/13/14's
 * pure-helper convention"). Extracted so the rule has one named, documented, tested home.
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
 * @param justCompleted  whether a run finished successfully within the success-dwell window. The
 *                   view layer sets this for ~4s after observing [Progress.Completed], then clears
 *                   it (lifecycle-scoped coroutine) so reality reasserts. Takes precedence over
 *                   IDLE_BACKLOG so a fresh nudge never displaces the "done" confirmation mid-dwell.
 */
internal fun bannerMode(
    pending: Boolean,
    backlog: Int,
    threshold: Int,
    snoozed: Boolean = false,
    justCompleted: Boolean = false
): BannerMode = when {
    justCompleted -> BannerMode.SUCCESS
    pending -> BannerMode.ACTIVE
    backlog > threshold && !snoozed -> BannerMode.IDLE_BACKLOG
    else -> BannerMode.HIDDEN
}
