package com.gesturemouse

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * An invisible text target for the phone's own keyboard that forwards typing
 * to the computer as HID keystrokes.
 *
 * Phone keyboards don't type a key at a time: they compose words, autocorrect
 * them, replace them wholesale after swipe or voice input, and delete by
 * editing surrounding text. So rather than translating each IME call, this
 * keeps a mirror of what has been typed since the keyboard opened and, after
 * every change, sends the difference — backspaces for what went away, keys for
 * what arrived. The computer's text field ends up matching the mirror whatever
 * the keyboard did to get there.
 *
 * That works because the cursor is always at the end: this view is never shown,
 * so nothing can move it. Keys that *would* move it (arrows, Home/End) are
 * forwarded to the computer without touching the mirror.
 */
class KeyboardInput @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mouse: HidMouse? = null

    /** Keyboard shown / hidden, so the button can reflect it. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    var isOpen = false
        private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // multi-line so Enter arrives as a newline instead of an editor action;
        // no fullscreen extract UI in landscape, which would cover the trackpad
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return Mirror()
    }

    fun open() {
        requestFocus()
        imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        setOpen(true)
    }

    fun close() {
        imm().hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
        setOpen(false)
    }

    fun toggle() = if (isOpen) close() else open()

    private fun setOpen(open: Boolean) {
        if (isOpen == open) return
        isOpen = open
        imeSeen = false
        onVisibilityChanged?.invoke(open)
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previous: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previous)
        if (!gained) setOpen(false)
    }

    /**
     * The keyboard can be dismissed without this view being told: the back
     * gesture (which on Android 13+ often never reaches the app at all), a
     * keyboard's own hide key, a manufacturer's swipe-down. So rather than
     * trust any one of those, watch whether the keyboard is actually on screen.
     * [imeSeen] guards the moment after [open], before it has slid in.
     */
    private var imeSeen = false

    private val imeWatcher = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (!isOpen) return@OnGlobalLayoutListener
        val visible = androidx.core.view.ViewCompat.getRootWindowInsets(this)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ?: return@OnGlobalLayoutListener
        if (visible) imeSeen = true
        else if (imeSeen) {
            clearFocus()
            setOpen(false)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(imeWatcher)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(imeWatcher)
        super.onDetachedFromWindow()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            clearFocus()
            setOpen(false)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun imm() =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private inner class Mirror : BaseInputConnection(this@KeyboardInput, true) {

        /** What the computer has been sent, as the mirror last stood. */
        private var sent = ""

        private fun sync() {
            val now = editable?.toString() ?: return
            if (now == sent) return
            var p = 0
            val max = minOf(now.length, sent.length)
            while (p < max && now[p] == sent[p]) p++
            val removed = sent.length - p
            val added = now.substring(p)
            mouse?.key(KeyMap.BACKSPACE, removed)
            mouse?.type(added)
            sent = now
            // Enter ends a line on the computer; start the mirror afresh so it
            // doesn't grow for the whole session. Only when nothing is being
            // composed, or the keyboard would lose the word it's building.
            if (now.endsWith("\n") && getComposingSpanStart(editable!!) < 0) reset()
        }

        private fun reset() {
            editable?.clear()
            sent = ""
            // tell the keyboard its text is gone too, or it keeps offering
            // corrections for words that are no longer there
            post { if (isOpen) imm().restartInput(this@KeyboardInput) }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int) =
            super.commitText(text, newCursorPosition).also { sync() }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
            super.setComposingText(text, newCursorPosition).also { sync() }

        override fun finishComposingText() =
            super.finishComposingText().also { sync() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val m = editable
            if (m == null || m.isEmpty()) {
                // nothing typed this session: the keyboard's backspace should
                // still delete on the computer
                mouse?.key(KeyMap.BACKSPACE, beforeLength)
                mouse?.key(KeyMap.DELETE, afterLength)
                return true
            }
            return super.deleteSurroundingText(beforeLength, afterLength).also { sync() }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val usage = when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    // keep the mirror honest if the backspace eats typed text
                    val m = editable
                    if (m != null && m.isNotEmpty()) {
                        m.delete(m.length - 1, m.length)
                        sync()
                        return true
                    }
                    KeyMap.BACKSPACE
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> KeyMap.DELETE
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    reset(); KeyMap.ENTER
                }
                KeyEvent.KEYCODE_TAB -> KeyMap.TAB
                KeyEvent.KEYCODE_ESCAPE -> KeyMap.ESCAPE
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyMap.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyMap.RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> KeyMap.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyMap.DOWN
                KeyEvent.KEYCODE_MOVE_HOME -> KeyMap.HOME
                KeyEvent.KEYCODE_MOVE_END -> KeyMap.END
                KeyEvent.KEYCODE_PAGE_UP -> KeyMap.PAGE_UP
                KeyEvent.KEYCODE_PAGE_DOWN -> KeyMap.PAGE_DOWN
                else -> {
                    val c = event.unicodeChar
                    if (c != 0) commitText(c.toChar().toString(), 1)
                    return true
                }
            }
            // cursor keys move the computer's caret away from the end, so the
            // mirror no longer describes what's around it — start it fresh
            if (usage != KeyMap.BACKSPACE && usage != KeyMap.ENTER) reset()
            mouse?.key(usage)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            reset()
            mouse?.key(KeyMap.ENTER)
            return true
        }
    }
}
