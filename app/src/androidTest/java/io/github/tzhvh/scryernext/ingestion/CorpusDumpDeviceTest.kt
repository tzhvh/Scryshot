/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * zvec Phase B, issue 02 B2 (bronze set) + B6 (D9) — corpus harvester.
 *
 * Walks the REAL app corpus via [ZvecContentStore.iterDocs] and dumps
 * (pk, content, locator, collection_id) to the app's external files dir for
 * `adb pull`. This is the full corpus, not a subset — the bronze-label design
 * derives everything (queries AND expected matches) from the harvested text
 * mechanically; no human curation enters the instrument.
 *
 * **Gated**: skips unless run with `-e dumpCorpus true`, so normal suite runs
 * never pay the walk (and never write the dump). Invocation:
 *
 * ```
 * ADB_SERVER_SOCKET=... ANDROID_SERIAL=<serial> \
 *   ./gradlew :app:connectedGoDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.dumpCorpus=true \
 *   --tests "io.github.tzhvh.scryernext.ingestion.CorpusDumpDeviceTest"
 * ```
 *
 * then `adb pull /sdcard/Android/data/io.github.tzhvh.scryernext.debug/files/corpus_dump.json`.
 *
 * Also records the D9 floor numbers (empty-content = failed-OCR docs that are
 * silently unsearchable) — the same walk, no extra pass.
 */
@RunWith(AndroidJUnit4::class)
class CorpusDumpDeviceTest {

    @Test fun dumpCorpusForBronzeHarvest() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipped (pass -e dumpCorpus true to harvest the real corpus)",
            args.getString("dumpCorpus") == "true",
        )

        val store = ScryerApplication.getZvecContentStore()
        val docs = store.iterDocs(outputFields = listOf("content", "locator", "collection_id"))
        assertTrue("corpus empty — is this the right app build/device?", docs.isNotEmpty())

        var emptyContent = 0
        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }

        val json = buildString {
            append("[")
            docs.forEachIndexed { i, doc ->
                val content = (doc.fields["content"] as? ZvecValue.Str)?.value ?: ""
                if (content.isBlank()) emptyContent++
                val locator = (doc.fields["locator"] as? ZvecValue.Str)?.value ?: ""
                val collId = (doc.fields["collection_id"] as? ZvecValue.Str)?.value ?: ""
                if (i > 0) append(",")
                append("{\"pk\":\"${esc(doc.pk)}\",")
                append("\"content\":\"${esc(content)}\",")
                append("\"locator\":\"${esc(locator)}\",")
                append("\"collection_id\":\"${esc(collId)}\"}")
            }
            append("]")
        }

        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "corpus_dump.json")
        out.writeText(json)

        val d9Floor = "%.1f".format(100.0 * emptyContent / docs.size)
        val message = "CORPUS DUMP: ${docs.size} docs → ${out.absolutePath}; D9 empty-content: $emptyContent/${docs.size} ($d9Floor%)"
        Log.i("CorpusDump", message)
        println(message)
    }
}
