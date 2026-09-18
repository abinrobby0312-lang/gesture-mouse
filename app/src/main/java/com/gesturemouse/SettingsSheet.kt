package com.gesturemouse

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.gesturemouse.databinding.ItemTrackerBinding
import com.gesturemouse.databinding.SheetSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import java.util.Locale

/**
 * Settings, as a sheet over the running app.
 *
 * A sheet rather than its own screen so the mouse stays live underneath:
 * every change applies the moment it's made, and the sensitivity trackers keep
 * watching real use while the sheet is closed.
 */
class SettingsSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "settings"
        private const val KEY_TAB = "tab"
        const val CONTACT_EMAIL = "abinrobby0312@gmail.com"

        fun show(activity: MainActivity) {
            if (activity.supportFragmentManager.findFragmentByTag(TAG) == null) {
                SettingsSheet().show(activity.supportFragmentManager, TAG)
            }
        }
    }

    private var _b: SheetSettingsBinding? = null
    private val b get() = _b!!

    private val settings by lazy { Settings.get(requireContext()) }
    private val main: MainActivity? get() = activity as? MainActivity

    private val pages by lazy {
        listOf(
            "Sensitivity" to b.pageSensitivity,
            "Scrolling" to b.pageScrolling,
            "Appearance" to b.pageAppearance,
            "Connection" to b.pageConnection,
            "Help & contact" to b.pageHelp
        )
    }

    private var stopListening: (() -> Unit)? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderTrackers()
            renderConnection()
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = SheetSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        // A fixed height, not wrap_content: the pages differ in length, and a
        // sheet that resizes on every tab switch moves the tab row out from
        // under the next tap. Open fully — half-expanded hides the content.
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams = sheet.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            }
        }
        d.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.close.setOnClickListener { dismiss() }

        pages.forEach { (title, _) -> b.tabs.addTab(b.tabs.newTab().setText(title)) }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        val start = savedInstanceState?.getInt(KEY_TAB) ?: 0
        b.tabs.getTabAt(start)?.select()
        showPage(start)

        bindSensitivity()
        bindScrolling()
        bindAppearance()
        bindConnection()
        bindHelp()

        stopListening = settings.listen { if (_b != null) renderValues() }
        renderValues()
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _b?.let { outState.putInt(KEY_TAB, it.tabs.selectedTabPosition) }
    }

    override fun onDestroyView() {
        stopListening?.invoke()
        stopListening = null
        ticker.removeCallbacks(tick)
        _b = null
        super.onDestroyView()
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, (_, page) -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        b.scroll.scrollTo(0, 0)
    }

    // ---------------------------------------------------------------- pages

    private fun bindSensitivity() {
        b.trackpadSpeed.addOnChangeListener { _, v, fromUser -> if (fromUser) settings.trackpadSpeed = v }
        b.airSpeed.addOnChangeListener { _, v, fromUser -> if (fromUser) settings.airSpeed = v }
        b.trackpadSpeed.setLabelFormatter { fmtTrackpad(it) }
        b.airSpeed.setLabelFormatter { fmtAir(it) }
        b.resetDefaults.setOnClickListener {
            settings.resetToDefaults()
            toast("Settings reset")
        }
    }

    private fun bindScrolling() {
        b.scrollSpeed.addOnChangeListener { _, v, fromUser -> if (fromUser) settings.scrollSpeed = v }
        b.scrollSpeed.setLabelFormatter { fmtMultiplier(it) }
        b.naturalScroll.setOnCheckedChangeListener { btn, checked ->
            if (btn.isPressed) settings.naturalScroll = checked
        }
    }

    private fun bindAppearance() {
        b.themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val chosen = when (id) {
                R.id.themeLight -> Settings.Theme.LIGHT
                R.id.themeDark -> Settings.Theme.DARK
                else -> Settings.Theme.SYSTEM
            }
            // setting it recreates the activity; this sheet comes back with it
            if (chosen != settings.theme) settings.theme = chosen
        }
    }

    private fun bindConnection() {
        b.howToConnect.setOnClickListener { dismiss(); main?.showConnectHelp() }
        b.makeVisible.setOnClickListener { main?.makePhoneVisible() }
        b.bluetoothSettings.setOnClickListener { main?.openBluetoothSettings() }
        b.forgetHost.setOnClickListener {
            main?.mouse?.forgetRememberedHost()
            renderConnection()
            toast("The app won't reconnect to that computer on its own now")
        }
    }

    private fun bindHelp() {
        b.showWalkthrough.setOnClickListener {
            val fm = parentFragmentManager   // before dismiss() detaches this
            dismiss()
            TutorialDialog.show(fm)
        }
        b.contactEmail.setOnClickListener { emailUs() }
        b.versionInfo.text = "Gesture Mouse ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }

    // ---------------------------------------------------------------- render

    private fun renderValues() {
        b.trackpadSpeed.value = settings.trackpadSpeed
        b.trackpadSpeedValue.text = fmtTrackpad(settings.trackpadSpeed)
        b.airSpeed.value = settings.airSpeed
        b.airSpeedValue.text = fmtAir(settings.airSpeed)
        b.scrollSpeed.value = settings.scrollSpeed
        b.scrollSpeedValue.text = fmtMultiplier(settings.scrollSpeed)
        b.naturalScroll.isChecked = settings.naturalScroll
        b.themeGroup.check(
            when (settings.theme) {
                Settings.Theme.SYSTEM -> R.id.themeSystem
                Settings.Theme.LIGHT -> R.id.themeLight
                Settings.Theme.DARK -> R.id.themeDark
            }
        )
        renderTrackers()
    }

    private fun renderTrackers() {
        val m = main ?: return
        renderTracker(
            b.trackpadTracker, m.trackpadTracker.report(), "trackpad",
            current = settings.trackpadSpeed,
            fmt = ::fmtTrackpad,
            apply = { settings.trackpadSpeed = it },
            snapped = Settings::snapTrackpad
        )
        renderTracker(
            b.airTracker, m.airTracker.report(), "air gestures",
            current = settings.airSpeed,
            fmt = ::fmtAir,
            apply = { settings.airSpeed = it },
            snapped = Settings::snapAir
        )
    }

    private fun renderTracker(
        card: ItemTrackerBinding,
        r: SensitivityTracker.Report,
        what: String,
        current: Float,
        fmt: (Float) -> String,
        apply: (Float) -> Unit,
        snapped: (Float) -> Float
    ) {
        val (color, text) = when (r.verdict) {
            SensitivityTracker.Verdict.LEARNING -> R.color.dim to
                    "Watching how you use the $what. Move to things as you normally " +
                    "would — ${r.aims} of ${SensitivityTracker.MIN_AIMS} moves seen so far."
            SensitivityTracker.Verdict.GOOD -> R.color.ok to
                    "Looks right. ${r.aims - r.overshoots - r.clutches} of your last ${r.aims} " +
                    "moves landed without overshooting or re-stroking."
            SensitivityTracker.Verdict.TOO_FAST -> R.color.fire to
                    "A bit fast: you overshot and came back on ${r.overshoots} of your " +
                    "last ${r.aims} moves."
            SensitivityTracker.Verdict.TOO_SLOW -> R.color.fire to
                    "A bit slow: you had to stroke again to get there on ${r.clutches} " +
                    "of your last ${r.aims} moves."
        }
        (card.trackerDot.background.mutate() as? GradientDrawable)
            ?.setColor(ContextCompat.getColor(requireContext(), color))

        val suggestion = if (r.factor != 1f) snapped(current * r.factor) else current
        val canApply = suggestion != current
        card.trackerText.text = if (r.factor != 1f && !canApply) {
            "$text It's already at the ${if (r.factor < 1f) "lowest" else "highest"} setting."
        } else text
        card.trackerApply.visibility = if (canApply) View.VISIBLE else View.GONE
        card.trackerApply.text = "Set to ${fmt(suggestion)}"
        card.trackerApply.setOnClickListener {
            apply(suggestion)
            toast("Speed set to ${fmt(suggestion)} — the tracker starts fresh")
        }
    }

    private fun renderConnection() {
        val m = main ?: return
        b.connectionStatus.text = m.statusSummary()
        val remembered = m.mouse?.rememberedHostName
        b.rememberedHost.text = if (remembered != null) {
            "Reconnects to $remembered when the app opens."
        } else {
            "No computer remembered yet — the first one that connects will be."
        }
        b.forgetHost.isEnabled = remembered != null
    }

    // ---------------------------------------------------------------- contact

    /**
     * Opens the person's own email app, addressed and with the details that
     * matter for a device-specific problem filled in. Nothing is sent by the
     * app itself; they see and send the email.
     */
    private fun emailUs() {
        val body = "\n\n\n—\nPlease keep these details, they help with device-specific problems:\n" +
                "App: Gesture Mouse ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Status: ${main?.statusSummary() ?: "unknown"}"
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "Gesture Mouse ${BuildConfig.VERSION_NAME} — feedback")
            .putExtra(Intent.EXTRA_TEXT, body)
        try {
            startActivity(Intent.createChooser(intent, "Email us"))
        } catch (_: ActivityNotFoundException) {
            copyAddress()
        }
    }

    /**
     * No email app at all (some work profiles, some stripped-down phones). The
     * address goes to the clipboard rather than on screen: it's only ever
     * shown inside an email the person chose to write.
     */
    private fun copyAddress() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("email", CONTACT_EMAIL))
        toast("No email app found — our address is copied, paste it into any mail app")
    }

    // ---------------------------------------------------------------- format

    private fun fmtTrackpad(v: Float) = String.format(Locale.US, "%.1f×", v)
    private fun fmtMultiplier(v: Float) = String.format(Locale.US, "%.1f×", v)
    /** The air speed has no natural unit; shown relative to the default. */
    private fun fmtAir(v: Float) = String.format(Locale.US, "%.2f×", v / Settings.AIR_SPEED_DEFAULT)

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
