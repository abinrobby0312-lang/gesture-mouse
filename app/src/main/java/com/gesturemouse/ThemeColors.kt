package com.gesturemouse

import android.content.Context
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors

/**
 * A colour from the current theme, e.g. [R.attr.brandAccent] — which one
 * depends on the accent chosen in Settings, so it can't be a fixed
 * `R.color` lookup. Magenta as the fallback makes a missing attribute obvious.
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

/** [color] with its alpha replaced by [alpha] (0–255). */
@ColorInt
fun withAlpha(@ColorInt color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
