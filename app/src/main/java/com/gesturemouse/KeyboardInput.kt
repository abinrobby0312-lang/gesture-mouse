package com.gesturemouse

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * An invisible text field for the phone's own keyboard, forwarding what's typed
 * to the computer as HID keystrokes.
 *
 * ## Why a real text field
 *
 * The first version was a bare [android.view.View] with a hand-written
 * `InputConnection` that overrode the handful of calls Gboard happens to use.
 * On OnePlus phones (Android 14 and 16) the mouse worked and the keyboard did
 * nothing: their keyboard revises text through calls that weren't overridden —
 * `replaceText` (added in Android 14), `commitCorrection`, the code-point
 * deletes, paste — and each one changed the text while sending no keystrokes.
 *
 * An `EditText` gets Android's own full `InputConnection`, so every keyboard's
 * edits land in one place: the text. This watches the text and sends whatever
 * changed ([KeyboardMirror]), so it no longer matters *how* a keyboard edits.
 *
 * The field is never visible — 1dp and fully transparent — so nothing can move
 * the cursor by touch. Keys that would move the computer's caret (arrows,
 * Home/End, page keys) are forwarded and reset the mirror, since after them it
 * can no longer describe what surrounds the caret.
 */
class KeyboardInput @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var mouse: HidMouse? = null

    /** Keyboard shown / hidden, so the button can reflect it. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    var isOpen = false
        private set

    private val mirror = KeyboardMirror()

    /** Set while the app rewrites the field itself, so it sends nothing. */
    private var selfEdit = false

    init {
        // invisible, but focusable: it must be a real editor for the keyboard
        alpha = 0f
        background = null
        isCursorVisible = false
        setPadding(0, 0, 0, 0)
        isSaveEnabled = false          // never restore old text into a live session
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO   // no autofill popups over the trackpad
        setTextIsSelectable(false)
        // not focusable until asked for: a real EditText would otherwise be the
        // first focusable view on the tab and could raise the keyboard by itself
        isFocusable = false
        isFocusableInTouchMode = false
        // multi-line so Enter is a newline rather than an editor action, and no
        // fullscreen extract UI in landscape, which would cover the trackpad
        setSingleLine(false)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onFieldChanged(s?.toString() ?: "")
        })
    }

    /** Any edit at all, by any keyboard, through any route. */
    private fun onFieldChanged(now: String) {
        if (selfEdit) return
        val diff = mirror.update(now)
        if (!diff.isEmpty) {
            Log.i(HidMouse.TAG, "keyboard: -${diff.backspaces} +${diff.text.length} char(s)")
            mouse?.key(KeyMap.BACKSPACE, diff.backspaces)
            mouse?.type(diff.text)
        }
        // Enter has been sent; start again so the field doesn't grow all
        // session. Same once it gets long — but never mid-word, or the keyboard
        // loses the word it's composing.
        val composing = text?.let { BaseInputConnection.getComposingSpanStart(it) >= 0 } ?: false
        if (!composing && (now.endsWith("\n") || mirror.shouldTrim(now.length))) clearField()
    }

    /** Empty the field without sending anything. */
    private fun clearField() {
        selfEdit = true
        try {
            text?.clear()
            mirror.reset()
        } finally {
            selfEdit = false
        }
    }

    // ---------------------------------------------------------------- keys

    /**
     * Keys that aren't text: the keyboard's backspace on an empty field, and
     * anything that moves the caret. Text keys are left alone — they change the
     * field, and [onFieldChanged] sends them.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val usage = when (keyCode) {
            // with text in the field these edit it, and the change is sent by
            // onFieldChanged; on an empty field they belong to the computer
            KeyEvent.KEYCODE_DEL ->
                if (length() > 0) return super.onKeyDown(keyCode, event) else KeyMap.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL ->
                if (length() > 0) return super.onKeyDown(keyCode, event) else KeyMap.DELETE
            KeyEvent.KEYCODE_ESCAPE -> KeyMap.ESCAPE
            KeyEvent.KEYCODE_TAB -> KeyMap.TAB
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyMap.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyMap.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> KeyMap.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyMap.DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> KeyMap.HOME
            KeyEvent.KEYCODE_MOVE_END -> KeyMap.END
            KeyEvent.KEYCODE_PAGE_UP -> KeyMap.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> KeyMap.PAGE_DOWN
            else -> return super.onKeyDown(keyCode, event)
        }
        Log.i(HidMouse.TAG, "keyboard: key 0x${usage.toString(16)}")
        mouse?.key(usage)
        // the caret has moved somewhere the mirror can't describe, or the field
        // was empty and the computer's text is no longer ours to reason about
        clearField()
        return true
    }

    /** Some keyboards send Enter as a key rather than a newline. */
    override fun onEditorAction(actionCode: Int) {
        mouse?.key(KeyMap.ENTER)
        clearField()
    }

    // ---------------------------------------------------------------- open/close

    fun open() {
        isFocusable = true
        isFocusableInTouchMode = true
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
        if (!open) { isFocusable = false; isFocusableInTouchMode = false }
        imeSeen = false
        // each session starts fresh: whatever is on the computer now is not
        // something this field can claim to have typed
        clearField()
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
        val visible = ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: return@OnGlobalLayoutListener
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
}
