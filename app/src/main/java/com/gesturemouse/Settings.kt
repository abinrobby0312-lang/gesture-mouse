package com.gesturemouse

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * User-adjustable feel, persisted across launches.
 *
 * One instance per process ([get]) so the settings sheet, the trackpad and the
 * Air tab all see the same values, and a change made in the sheet reaches the
 * live pointer immediately through [listen].
 */
class Settings private constructor(context: Context) {

    companion object {
        private const val PREFS = "gesturemouse.settings"

        const val TRACKPAD_SPEED_DEFAULT = 1.2f
        const val TRACKPAD_SPEED_MIN = 0.4f
        const val TRACKPAD_SPEED_MAX = 3.0f
        const val TRACKPAD_SPEED_STEP = 0.1f

        const val AIR_SPEED_DEFAULT = 1800f
        const val AIR_SPEED_MIN = 400f
        const val AIR_SPEED_MAX = 4000f
        const val AIR_SPEED_STEP = 100f

        const val SCROLL_SPEED_DEFAULT = 1.0f
        const val SCROLL_SPEED_MIN = 0.4f
        const val SCROLL_SPEED_MAX = 2.5f
        const val SCROLL_SPEED_STEP = 0.1f

        fun snapTrackpad(v: Float) = snap(v, TRACKPAD_SPEED_MIN, TRACKPAD_SPEED_MAX, TRACKPAD_SPEED_STEP)
        fun snapAir(v: Float) = snap(v, AIR_SPEED_MIN, AIR_SPEED_MAX, AIR_SPEED_STEP)
        fun snapScroll(v: Float) = snap(v, SCROLL_SPEED_MIN, SCROLL_SPEED_MAX, SCROLL_SPEED_STEP)

        private fun snap(v: Float, min: Float, max: Float, step: Float): Float {
            val steps = Math.round((v.coerceIn(min, max) - min) / step)
            // round again to kill float noise like 1.2000001, which the slider rejects
            return Math.round((min + steps * step) * 1000f) / 1000f
        }

        @Volatile private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    // Every value is snapped to its slider's step. A Material Slider throws if
    // handed a value off its grid, and tracker suggestions (x0.85, x1.15) never
    // land on one by themselves.

    /** Multiplier on finger movement for the Trackpad tab. Step [TRACKPAD_SPEED_STEP]. */
    var trackpadSpeed: Float
        get() = prefs.getFloat("trackpadSpeed", TRACKPAD_SPEED_DEFAULT)
        set(v) = put("trackpadSpeed", snapTrackpad(v))

    /** [GestureEngine.speed] for the Air tab. Step [AIR_SPEED_STEP]. */
    var airSpeed: Float
        get() = prefs.getFloat("airSpeed", AIR_SPEED_DEFAULT)
        set(v) = put("airSpeed", snapAir(v))

    /** Scroll notches per unit of movement, relative to the default, both tabs. */
    var scrollSpeed: Float
        get() = prefs.getFloat("scrollSpeed", SCROLL_SPEED_DEFAULT)
        set(v) = put("scrollSpeed", snapScroll(v))

    /** "Natural" scrolling: content follows the fingers, as on a phone. */
    var naturalScroll: Boolean
        get() = prefs.getBoolean("naturalScroll", false)
        set(v) { prefs.edit().putBoolean("naturalScroll", v).apply(); notifyChanged() }

    enum class Theme(val nightMode: Int) {
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        DARK(AppCompatDelegate.MODE_NIGHT_YES)
    }

    /** Light, dark, or whatever the phone is set to. Default: dark, the original look. */
    var theme: Theme
        get() = prefs.getString("theme", null)
            ?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.DARK
        set(v) {
            if (v == theme) return
            prefs.edit().putString("theme", v.name).apply()
            applyTheme()
            notifyChanged()
        }

    /**
     * Push [theme] to AppCompat. Changing it recreates the activity, which
     * briefly drops and restores the Bluetooth connection — the same as the
     * phone switching dark mode on its own.
     */
    fun applyTheme() {
        if (AppCompatDelegate.getDefaultNightMode() != theme.nightMode) {
            AppCompatDelegate.setDefaultNightMode(theme.nightMode)
        }
    }

    /**
     * Back to defaults — except the theme. Resetting sensitivity shouldn't
     * also flip the screen from light to dark under someone.
     */
    fun resetToDefaults() {
        val keepTheme = prefs.getString("theme", null)
        val edit = prefs.edit().clear()
        if (keepTheme != null) edit.putString("theme", keepTheme)
        edit.apply()
        notifyChanged()
    }

    private fun put(key: String, v: Float) {
        if (prefs.getFloat(key, Float.NaN) == v) return
        prefs.edit().putFloat(key, v).apply()
        notifyChanged()
    }

    /** Called on every change. Returns a handle that stops listening. */
    fun listen(onChange: () -> Unit): () -> Unit {
        listeners += onChange
        return { listeners -= onChange }
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }
}
