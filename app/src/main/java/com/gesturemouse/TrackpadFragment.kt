package com.gesturemouse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.gesturemouse.databinding.FragmentTrackpadBinding

/** The plain touch trackpad tab, for when waving at the camera isn't wanted. */
class TrackpadFragment : Fragment() {

    private var _b: FragmentTrackpadBinding? = null
    private val b get() = _b!!

    private val mouse: HidMouse? get() = (activity as? MainActivity)?.mouse

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentTrackpadBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.surface.mouse = mouse

        // hold-to-press rather than click, so these can be used for dragging
        holdButton(b.btnLeft, HidMouse.BUTTON_LEFT)
        holdButton(b.btnMiddle, HidMouse.BUTTON_MIDDLE)
        holdButton(b.btnRight, HidMouse.BUTTON_RIGHT)

        // There's no way to open this automatically when a text field on the
        // computer gets focus: a HID keyboard hears nothing from the host but
        // its LED state. So it's a button.
        b.keyboardInput.mouse = mouse
        b.keyboardInput.onVisibilityChanged = { open -> b.btnKeyboard.isChecked = open }
        b.btnKeyboard.isCheckable = true
        b.btnKeyboard.setOnClickListener {
            if (mouse?.isConnected != true) {
                b.btnKeyboard.isChecked = false
                android.widget.Toast.makeText(
                    requireContext(), "Connect to a computer first", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            b.keyboardInput.toggle()
            b.btnKeyboard.isChecked = b.keyboardInput.isOpen
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun holdButton(v: View, mask: Int) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { mouse?.buttonDown(mask); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mouse?.buttonUp(mask); true }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.surface.mouse = mouse
        b.keyboardInput.mouse = mouse
    }

    override fun onPause() {
        super.onPause()
        mouse?.releaseButtons()
        // switching to the Air tab shouldn't leave the keyboard up over it
        if (b.keyboardInput.isOpen) b.keyboardInput.close()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
