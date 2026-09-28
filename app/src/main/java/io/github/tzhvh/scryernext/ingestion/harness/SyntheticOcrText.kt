/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.harness

import java.util.Random
import kotlin.math.pow

/**
 * Deterministic synthetic OCR-text generator for the ingestion repro harness
 * (`docs/INGESTION_ADHOC_REPRO_HARNESS.md` §10 caveat: "synthetic text must be
 * realistic — FTS + `content_ngram` index growth depends on text length and
 * distribution"). A naive `"synth doc $i"` under-tokenizes and under-represents
 * the H1/H4 zvec-memory curve; this generator shapes each document like real
 * screenshot OCR output instead:
 *
 * - a **length distribution** (30–120 words) with a mid-90s mean — phone-screen
 *   OCR text is short-to-medium prose, not fixed-size records;
 * - **boilerplate repetition** across documents (system-UI labels recur in every
 *   screenshot corpus) so the inverted/FTS postings grow like real ones;
 * - a **Zipf-ish word distribution** (cubed-uniform head bias) — natural text is
 *   heavy-tailed, and the ngram index's memory curve follows the tail;
 * - per-document **unique tokens** (a salt word + the doc index) so every doc is
 *   content-distinct — a repeated corpus would collapse under the sink's
 *   content_hash dedup and write nothing.
 *
 * Pure JVM, seeded from one `Random` → byte-identical output for the same seed.
 * Single-threaded by construction (each call advances the generator); the
 * harness factories call it from one thread.
 */
object SyntheticOcrText {

    /**
     * Screenshot-typical vocabulary, grouped so a doc mixes registers the way a
     * phone screen does (system chrome + one app's content). Order is irrelevant;
     * the sampler shuffles.
     */
    private val SYSTEM_WORDS = listOf(
        "settings", "wifi", "bluetooth", "battery", "display", "notifications",
        "storage", "security", "accounts", "accessibility", "about", "phone",
        "network", "connection", "preferences", "permissions", "updates",
        "system", "apps", "default", "enable", "disable", "sync", "backup",
        "device", "care", "maintenance", "advanced", "features", "mode",
    )

    private val CHAT_WORDS = listOf(
        "message", "sent", "reply", "forward", "attachment", "photo", "video",
        "voice", "call", "missed", "chat", "group", "unread", "typing", "online",
        "yesterday", "morning", "tonight", "meeting", "lunch", "dinner", "home",
        "work", "office", "tomorrow", "today", "thanks", "please", "sorry",
        "great", "fine", "okay", "sure", "later", "soon", "done", "waiting",
    )

    private val WORK_WORDS = listOf(
        "invoice", "payment", "order", "shipping", "tracking", "delivery",
        "account", "statement", "balance", "receipt", "total", "tax", "discount",
        "subscription", "renewal", "trial", "plan", "billing", "invoice",
        "confirm", "verify", "password", "reset", "login", "signup", "email",
        "address", "phone", "contact", "profile", "dashboard", "report",
        "quarterly", "revenue", "growth", "metrics", "deadline", "review",
    )

    private val BOILERPLATE = listOf(
        "Do not disturb", "Screen timeout", "Data saver is on",
        "Battery saver enabled", "Connected to Wi-Fi", "No signal",
        "Swipe down to refresh", "Tap to retry", "Loading content",
        "Sign in to continue", "Allow access", "Storage almost full",
        "Software update available", "Backup completed", "Sync in progress",
    )

    private val ALL_WORDS = SYSTEM_WORDS + CHAT_WORDS + WORK_WORDS

    /**
     * One document of OCR-shaped text. [index] both salts the content (every doc
     * unique → dedup cannot collapse the corpus) and appears literally, so a
     * stranded harness doc found later in an inspector is attributable to its run.
     */
    fun document(index: Int, random: Random, includeHarnessMarker: Boolean = true): String {
        val wordCount = MIN_WORDS + random.nextInt(MAX_WORDS - MIN_WORDS + 1)
        val sb = StringBuilder(wordCount * 7)
        if (includeHarnessMarker) {
            sb.append("harness synthetic doc ").append(index).append(' ')
        }
        // 1–3 boilerplate lines (system chrome recurs across screenshots).
        repeat(random.nextInt(3)) { pick ->
            if (pick > 0 || random.nextBoolean()) {
                sb.append(BOILERPLATE[random.nextInt(BOILERPLATE.size)]).append(". ")
            }
        }
        repeat(wordCount) { i ->
            when {
                // Zipf-ish head bias: natural text is heavy-tailed; the cubed
                // uniform puts most picks in the bank's head while the tail still
                // grows the ngram index.
                random.nextInt(100) < 85 -> sb.append(ALL_WORDS[zipfPick(random)])
                // Numbers/times/urls — screenshots are full of them; they exercise
                // the tokenizer's non-word paths.
                random.nextInt(100) < 50 -> sb.append(random.nextInt(10_000))
                else -> sb.append("https://x.co/").append(random.nextInt(1_000_000).toString(36))
            }
            sb.append(if (i == wordCount - 1) "." else " ")
            if (random.nextInt(24) == 0) sb.append("\n")
        }
        // Unique tail token per doc — the dedup guarantee (§10's "naive
        // synth doc under-tokenizes" caveat applies to uniqueness too).
        sb.append(" ref ").append(saltWord(random)).append(index)
        return sb.toString()
    }

    private fun zipfPick(random: Random): Int {
        val u = random.nextDouble().pow(3.0)
        return (u * ALL_WORDS.size).toInt().coerceIn(0, ALL_WORDS.size - 1)
    }

    private fun saltWord(random: Random): String =
        listOf("kappa", "zeta", "mica", "onyx", "ferro", "lumen", "quartz", "delta")[random.nextInt(8)]

    private const val MIN_WORDS = 30
    private const val MAX_WORDS = 120
}
