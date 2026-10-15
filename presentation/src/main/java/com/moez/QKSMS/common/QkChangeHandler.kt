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
package dev.octoshrimpy.quik.common

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.changehandler.AnimatorChangeHandler
import dev.octoshrimpy.quik.common.util.extensions.dpToPx

class QkChangeHandler : AnimatorChangeHandler(250, true) {

    companion object {
        fun calculateEffectiveWidth(viewWidth: Int, containerWidth: Int, displayWidthPixels: Int): Float {
            return when {
                viewWidth > 0 -> viewWidth.toFloat()
                containerWidth > 0 -> containerWidth.toFloat()
                else -> displayWidthPixels.toFloat()
            }
        }
    }

    override fun getAnimator(
        container: ViewGroup,
        from: View?,
        to: View?,
        isPush: Boolean,
        toAddedToContainer: Boolean
    ): Animator {
        val animatorSet = AnimatorSet()
        animatorSet.interpolator = DecelerateInterpolator()

        val displayWidth = container.resources.displayMetrics.widthPixels
        val containerWidth = container.width

        if (isPush) {
            if (from != null) {
                val fromWidth = calculateEffectiveWidth(from.width, containerWidth, displayWidth)
                animatorSet.play(ObjectAnimator.ofFloat(from, View.TRANSLATION_X, -fromWidth / 4))
            }
            if (to != null) {
                to.translationZ = 8.dpToPx(to.context).toFloat()
                val toWidth = calculateEffectiveWidth(to.width, containerWidth, displayWidth)
                animatorSet.play(ObjectAnimator.ofFloat(to, View.TRANSLATION_X, toWidth, 0f))
            }
        } else {
            if (from != null) {
                from.translationZ = 8.dpToPx(from.context).toFloat()
                val fromWidth = calculateEffectiveWidth(from.width, containerWidth, displayWidth)
                animatorSet.play(ObjectAnimator.ofFloat(from, View.TRANSLATION_X, fromWidth))
            }
            if (to != null) {
                // Allow this to have a nice transition when coming off an aborted push animation
                val fromLeft = from?.translationX ?: 0f
                val toWidth = calculateEffectiveWidth(to.width, containerWidth, displayWidth)
                animatorSet.play(ObjectAnimator.ofFloat(to, View.TRANSLATION_X, fromLeft - toWidth / 4, 0f))
            }
        }

        return animatorSet
    }

    override fun resetFromView(from: View) {
        from.translationX = 0f
        from.translationZ = 0f
    }

    override fun copy(): ControllerChangeHandler {
        return QkChangeHandler()
    }

}
