/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.google.android.material.materialswitch.MaterialSwitch
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject

class QkSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle
) : MaterialSwitch(context, attrs, defStyleAttr) {

    @Inject lateinit var colors: Colors
    @Inject lateinit var prefs: Preferences

    init {
        if (!isInEditMode) {
            appComponent.inject(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!isInEditMode) {
            val themeColor = colors.theme().theme
            val onThemeColor = colors.theme().textPrimary

            val thumbStates = arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            )
            thumbTintList = ColorStateList(thumbStates, intArrayOf(
                context.resolveThemeColor(R.attr.switchThumbDisabled),
                onThemeColor,
                context.resolveThemeColor(R.attr.switchThumbEnabled)
            ))

            val trackStates = arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            )
            trackTintList = ColorStateList(trackStates, intArrayOf(
                context.resolveThemeColor(R.attr.switchTrackDisabled),
                themeColor,
                context.resolveThemeColor(R.attr.switchTrackEnabled)
            ))
        }
    }
}