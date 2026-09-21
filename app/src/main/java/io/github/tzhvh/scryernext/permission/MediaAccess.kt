/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.permission

/**
 * ADR 0008 — the media-permission tri-state. API 34's photo picker makes
 * "partial" (`READ_MEDIA_VISUAL_USER_SELECTED`) a real, reachable state:
 * the user granted access to only the photos they picked. Partial is a
 * setup-hub condition, not a wizard blocker — the wizard treats it as
 * success-adjacent; only the hub says "some screenshots may be missing".
 */
enum class MediaAccess {
    /** Full grant: `READ_MEDIA_IMAGES` (33+) or `READ_EXTERNAL_STORAGE` (29–32). */
    GRANTED,

    /** API 34+ only: the user shared selected photos ("Select photos"). */
    PARTIAL,

    /** Nothing granted. */
    DENIED
}
