package com.gesturemouse

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.gesturemouse.databinding.DialogTutorialBinding
import com.gesturemouse.databinding.ItemTutorialPageBinding

/**
 * The first-run walkthrough.
 *
 * Shown once automatically, then never again unless asked for — the flag lives
 * in [PREFS] so it survives reinstall-free app restarts, and Settings → Help &
 * contact ([SettingsSheet]) reopens it on demand. Nothing here is interactive beyond
 * paging; it exists because none of the gestures are discoverable by poking at
 * the screen, and a Bluetooth mouse that needs pairing before it does anything
 * is a bad first impression without a word of explanation.
 */
class TutorialDialog : DialogFragment() {

    private data class Page(
        val step: String, val title: String, val body: String, val logo: Boolean = false
    )

    private val pages = listOf(
        Page(
            "Step 1 of 5",
            "Your phone is the mouse",
            "Gesture Mouse turns this phone into a Bluetooth mouse. Your computer " +
                "needs nothing installed — it just sees an ordinary wireless mouse " +
                "and uses the driver it already has.\n\nEverything runs on the phone. " +
                "No video and no tracking data ever leave the device.",
            logo = true
        ),
        Page(
            "Step 2 of 5",
            "Pair it first",
            "Pair it like any Bluetooth mouse and keyboard.\n\n" +
                "1.  Keep this app open\n" +
                "2.  On the computer, open Bluetooth settings → Add device\n" +
                "3.  Pick this phone and confirm the code on both screens\n\n" +
                "The strip at the top shows when you're connected. After the first " +
                "time, just open the app and it reconnects. Tap the strip any time " +
                "for these steps."
        ),
        Page(
            "Step 3 of 5",
            "Trackpad tab",
            "An ordinary touch trackpad, following laptop conventions.\n\n" +
                "•  One finger — move the cursor\n" +
                "•  Tap — left click\n" +
                "•  Two-finger drag — scroll\n" +
                "•  Two-finger tap — right click\n" +
                "•  Tap, then press and hold — drag something\n" +
                "•  ⌨ in the corner — type on the computer with this phone's keyboard"
        ),
        Page(
            "Step 4 of 5",
            "Air tab",
            "Point the camera at your hand and don't touch anything.\n\n" +
                "•  Open palm — sweep fast across the screen\n" +
                "•  Point one finger — slow, precise aiming\n" +
                "•  Dip your index finger — click\n" +
                "•  Pinch and hold — grab and drag\n" +
                "•  Two fingers up, move up or down — scroll\n" +
                "•  Make a fist — lift off and reposition"
        ),
        Page(
            "Step 5 of 5",
            "It goes to sleep",
            "The camera sees everything in front of it, not just you. So after 5 " +
                "seconds without real use, the Air tab stops reacting entirely and " +
                "shows \"sleeping\".\n\nTo wake it, hold one open palm steady for 3 " +
                "seconds — there's a countdown on screen.\n\nYou can reopen this " +
                "guide any time from ⚙ Settings → Help & contact."
        )
    )

    private var _b: DialogTutorialBinding? = null
    private val b get() = _b!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_GestureMouse_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = DialogTutorialBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.tutorialPager.adapter = PageAdapter()
        TabLayoutMediator(b.tutorialDots, b.tutorialPager) { _, _ -> }.attach()

        b.tutorialSkip.setOnClickListener { finish() }
        b.tutorialNext.setOnClickListener {
            val next = b.tutorialPager.currentItem + 1
            if (next < pages.size) b.tutorialPager.currentItem = next else finish()
        }

        // last page says "Done" rather than "Next", so it's clear the button
        // closes rather than advancing into nothing
        b.tutorialPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val last = position == pages.lastIndex
                    b.tutorialNext.text = if (last) "Done" else "Next"
                    b.tutorialSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
                }
            }
        )
    }

    private fun finish() = dismissAllowingStateLoss()

    /**
     * Marking seen and reporting completion happen here rather than in
     * [finish], so the back button counts as finishing too — otherwise backing
     * out would leave the walkthrough queued to reappear forever, and (on
     * first run) leave permissions never requested.
     */
    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        context?.let { markSeen(it) }
        parentFragmentManager.setFragmentResult(RESULT_DISMISSED, Bundle.EMPTY)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
            ItemTutorialPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val p = pages[position]
            holder.binding.pageStep.text = p.step
            holder.binding.pageTitle.text = p.title
            holder.binding.pageBody.text = p.body
            holder.binding.pageLogo.visibility = if (p.logo) View.VISIBLE else View.GONE
        }
    }

    private class PageHolder(val binding: ItemTutorialPageBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val PREFS = "gesturemouse"
        private const val KEY_SEEN = "tutorialSeen"
        private const val TAG = "tutorial"

        /** Fired once the walkthrough closes, by any route. */
        const val RESULT_DISMISSED = "tutorialDismissed"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun hasBeenSeen(context: Context) = prefs(context).getBoolean(KEY_SEEN, false)

        fun markSeen(context: Context) =
            prefs(context).edit().putBoolean(KEY_SEEN, true).apply()

        /** Show it, unless it's already on screen. */
        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null) return
            TutorialDialog().show(fm, TAG)
        }
    }
}
