/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * The dedup fast path for content_hash identity (zvec Phase 2, issue 01).
 *
 * Keys on the cheap filesystem triple `(locator, mtime, size)` — the no-`open()` check —
 * and maps to the content_hash (the zvec PK) plus an indexed-status flag. `isKnown(candidate,
 * bytes)` (issue 02) consults [lookup] first; a hit returns [indexed] without hashing or opening
 * the file. Misses fall through to hashing + zvec PK existence (issue 03).
 *
 * Scope discipline — what this cache is NOT:
 * - NOT the backlog-count mechanism. The `DiscoveryWorker` backlog count is
 *   `SELECT COUNT(*) FROM screenshot WHERE processed = 0` (cheap); the cache's [indexed] column
 *   is not consulted for it. (The original roadmap framed the cache as load-bearing for both;
 *   that was demoted on 2026-07-01 — see `docs/ZVEC_PHASE2.md` § "The metadata cache".)
 * - NOT change-detection. The `(locator, mtime, size)` key is the *hook* for future
 *   change-detection; not this issue.
 *
 * See: [ADR 0004 §7.1](../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 * (partially retired), [ZVEC_PHASE2.md](../../../../docs/ZVEC_PHASE2.md) § "The metadata cache".
 */
@Entity(
        tableName = "content_metadata_cache",
        primaryKeys = ["locator", "mtime", "size"],
        indices = [Index("content_hash")]
)
data class ContentMetadataCache(
        @ColumnInfo(name = "locator") val locator: String,
        @ColumnInfo(name = "mtime") val mtime: Long,
        @ColumnInfo(name = "size") val size: Long,
        @ColumnInfo(name = "content_hash") val contentHash: String,
        @ColumnInfo(name = "indexed", defaultValue = "0") val indexed: Boolean = false
)
