/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.detailpage

import com.google.mlkit.vision.text.Text

class GraphicOverlayHelper(private val overlay: GraphicOverlay) {
    var blocks = listOf<TextBlockGraphic>()

    fun getSelectedText(): String {
        val selectedBlocks = blocks.filter { it.isSelected }
        return buildFullTextString(selectedBlocks)
    }

    fun selectAllBlocks() {
        blocks.forEach { it.isSelected = true }
    }

    fun unselectAllBlocks() {
        blocks.forEach { it.isSelected = false }
    }

    private fun buildFullTextString(blocks: List<TextBlockGraphic>): String {
        val builder = StringBuilder()
        blocks.forEach { block ->
            builder.append(buildLines(block)).append("\n")
        }
        return builder.trim().toString()
    }

    fun convertToGraphicBlocks(
            text: Text,
            pageView: DetailPageAdapter.PageView
    ): List<TextBlockGraphic> {
        return convertToGraphicBlocks(convertToTextBlocks(text), pageView, overlay)
    }

    @Suppress("unused")
    fun convertWordsToGraphicBlocks(
            text: Text,
            words: List<String>,
            pageView: DetailPageAdapter.PageView
    ): List<TextBlockGraphic> {
        val matchedElements = mutableListOf<Text.Element>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    for (word in words) {
                        if (element.text.contains(word, true)) {
                            matchedElements.add(element)
                        }
                    }
                }
            }
        }

        return matchedElements.map {
            TextBlockGraphic(overlay).apply {
                elementBlock = it
                transform(this, pageView)
            }
        }
    }

    private fun buildLines(block: TextBlockGraphic): String {
        val builder = StringBuilder()
        when {
            block.isTextBlock() -> {
                block.textBlock?.let {
                    builder.append(buildLine(it))
                }
            }

            block.isLineBlock() -> {
                block.lineBlock?.let {
                    builder.append(it.text)
                }
            }

            else -> {
                block.elementBlock?.let {
                    builder.append(it.text)
                }
            }
        }
        return builder.toString()
    }

    private fun buildLine(block: Text.TextBlock): String {
        val builder = StringBuilder()
        val lines = block.lines.toMutableList().apply {
            sortBy {
                it.boundingBox?.centerY()
            }
        }
        lines.forEach { line ->
            builder.append(line.text).append("\n")
        }
        return builder.toString()
    }

    private fun convertToTextBlocks(
            text: Text
    ): List<Text.TextBlock> {
        return getSortedTextBlocks(text)
    }

    private fun convertToGraphicBlocks(
            textBlocks: List<Text.TextBlock>,
            pageView: DetailPageAdapter.PageView,
            overlay: GraphicOverlay
    ): List<TextBlockGraphic> {
        return textBlocks.map { textBlock ->
            TextBlockGraphic(overlay).apply {
                this.textBlock = textBlock
                transform(this, pageView)
            }
        }
    }

    private fun transform(graphicBlock: TextBlockGraphic, pageView: DetailPageAdapter.PageView) {
        // Since the image will be displayed in the manner of center-inside, we need to
        // transform each block so its size and position can be rendered correctly
        val transform = getTransform(pageView.getWidth(), pageView.getHeight(),
                pageView.getSourceImageWidth(), pageView.getSourceImageHeight())
        transform(graphicBlock)
    }

    private fun getTransform(
            viewportWidth: Int,
            viewportHeight: Int,
            imageWidth: Int,
            imageHeight: Int
    ): (graphic: GraphicOverlay.Graphic) -> Unit {
        val imageRatio = imageWidth / imageHeight.toFloat()
        val viewportRatio = viewportWidth / viewportHeight.toFloat()

        val scale: Float
        var translationX = 0f
        var translationY = 0f
        if (imageRatio >= viewportRatio) {
            scale = viewportWidth / imageWidth.toFloat()
            translationY = (viewportHeight.toFloat() - imageHeight * scale) / 2f

        } else {
            scale = viewportHeight / imageHeight.toFloat()
            translationX = (viewportWidth.toFloat() - imageWidth * scale) / 2f
        }

        return {
            it.scale = scale
            it.translationX = translationX
            it.translationY = translationY
        }
    }

    private fun getSortedTextBlocks(result: Text): List<Text.TextBlock> {
        return result.textBlocks.toMutableList().apply {
            sortBy { it.boundingBox?.centerY() }
        }
    }
}
