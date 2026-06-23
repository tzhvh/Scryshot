/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import androidx.lifecycle.Observer

class Observer<T>(private val onEvent: (T) -> Unit) : Observer<T> {
    override fun onChanged(t: T) {
        onEvent(t)
    }
}

abstract class NonNullObserver<T> : Observer<T> {
    override fun onChanged(t: T) {
        onValueChanged(t)
    }

    abstract fun onValueChanged(newValue: T)
}
