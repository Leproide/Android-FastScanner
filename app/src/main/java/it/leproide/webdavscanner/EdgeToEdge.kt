/*
 * FastScanner
 * Copyright (C) 2026
 * Author: https://github.com/Leproide
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package it.leproide.webdavscanner

import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * Android 15+ (targetSdk 35) forces edge-to-edge: content is drawn behind the
 * status/navigation bars. Enable it on every API level for consistency and pad
 * the root view with system-bar + keyboard insets so nothing is overlapped.
 * Must be called before setContentView().
 */
fun AppCompatActivity.enableInsetsAwareLayout() {
    enableEdgeToEdge()
}

/** Call after setContentView(): installs the toolbar and applies insets to [root]. */
fun AppCompatActivity.setupToolbarAndInsets(root: View, toolbar: MaterialToolbar) {
    setSupportActionBar(toolbar)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
