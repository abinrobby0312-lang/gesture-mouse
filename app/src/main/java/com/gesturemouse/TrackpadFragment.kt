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
    }

    override fun onPause() {
        super.onPause()
        mouse?.releaseButtons()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
