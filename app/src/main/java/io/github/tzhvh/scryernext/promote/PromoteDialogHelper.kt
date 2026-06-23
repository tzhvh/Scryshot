/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.promote

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import io.github.tzhvh.scryernext.databinding.DialogPromoteBinding

class PromoteDialogHelper {
    companion object {
        fun createPromoteDialog(
                context: Context,
                title: String,
                subtitle: String,
                drawable: Drawable?,
                positiveText: String,
                positiveListener: () -> Unit,
                negativeText: String,
                negativeListener: () -> Unit
        ): AlertDialog {
            val dialog = AlertDialog.Builder(context).create()
            val binding = DialogPromoteBinding.inflate(LayoutInflater.from(context)).apply {
                this.title.text = title
                this.subtitle.text = subtitle
                drawable?.let { d ->
                    image.setImageDrawable(d)
                }

                positiveButton.text = positiveText
                positiveButton.setOnClickListener { _ ->
                    dialog.dismiss()
                    positiveListener.invoke()
                }

                negativeButton.text = negativeText
                negativeButton.setOnClickListener { _ ->
                    dialog.dismiss()
                    negativeListener.invoke()
                }
            }
            dialog.setView(binding.root)
            return dialog
        }
    }
}
