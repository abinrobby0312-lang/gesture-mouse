package com.gesturemouse

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.gesturemouse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    var mouse: HidMouse? = null
        private set

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startHid()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) TrackpadFragment() else AirFragment()
        }
        // keep both tabs alive so switching doesn't tear down the camera
        b.pager.offscreenPageLimit = 1

        // tabs switch by tap only. horizontal swipes are the trackpad's whole
        // job, so leaving the pager swipeable meant every sideways drag also
        // yanked you into the other tab.
        b.pager.isUserInputEnabled = false

        TabLayoutMediator(b.tabs, b.pager) { tab, pos ->
            tab.text = getString(if (pos == 0) R.string.tab_trackpad else R.string.tab_air)
        }.attach()

        b.statusStrip.setOnClickListener { showConnectHelp() }
        b.helpButton.setOnClickListener { TutorialDialog.show(supportFragmentManager) }

        android.util.Log.i(HidMouse.TAG, "MainActivity.onCreate")

        // First run only — guarded on savedInstanceState so a rotation doesn't
        // put it back up over whatever you were doing.
        //
        // On that first run the permission prompts wait until the walkthrough
        // is done. Asking first buries the explanation under a system dialog,
        // and asks for the camera before the user has been told the app is a
        // mouse that reads hand gestures — which is both a worse first
        // impression and a worse reason to tap Allow.
        val firstRun = savedInstanceState == null && !TutorialDialog.hasBeenSeen(this)
        if (firstRun) {
            supportFragmentManager.setFragmentResultListener(
                TutorialDialog.RESULT_DISMISSED, this
            ) { _, _ -> requestNeededPermissions() }
            TutorialDialog.show(supportFragmentManager)
        } else {
            requestNeededPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        // re-arm every time the app comes forward. registering only once after
        // the permission callback meant that any activity recreation left the
        // HID service silently unregistered, with the UI still claiming ready.
        android.util.Log.i(HidMouse.TAG, "MainActivity.onResume, mouse=${mouse != null}")
        startHid()
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startHid() else permissionLauncher.launch(missing.toTypedArray())
    }

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startHid() {
        mouse?.let {
            // an existing HidMouse is not proof of a live registration — the
            // stack drops it when a host disconnects, so ask it to self-heal
            android.util.Log.i(HidMouse.TAG, "startHid: existing mouse, registered=${it.registered}")
            it.ensureStarted()
            return
        }
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        android.util.Log.i(HidMouse.TAG, "startHid: BLUETOOTH_CONNECT granted=$granted")
        if (!granted) {
            showState(HidMouse.State.OFF, "Bluetooth permission denied")
            return
        }
        mouse = HidMouse(this).apply {
            onState = { state, msg -> showState(state, msg) }
            start()
        }
    }

    private var lastState = HidMouse.State.REGISTERING
    private var lastMsg = ""

    /**
     * The strip only reports. Pairing happens in the computer's Bluetooth
     * settings like any other mouse; tapping the strip says how.
     */
    private fun showState(state: HidMouse.State, msg: String) {
        lastState = state
        lastMsg = msg
        val color = ContextCompat.getColor(this, when (state) {
            HidMouse.State.CONNECTED -> R.color.track
            HidMouse.State.REGISTERING, HidMouse.State.CONNECTING -> R.color.fire
            HidMouse.State.WAITING -> R.color.dim
            else -> R.color.fault
        })
        (b.statusDot.background as? GradientDrawable)?.setColor(color)
        b.statusText.text = when (state) {
            HidMouse.State.CONNECTED -> "Connected to $msg"
            HidMouse.State.REGISTERING, HidMouse.State.CONNECTING -> msg
            HidMouse.State.WAITING, HidMouse.State.STALE_BOND -> "Not connected — tap to connect"
            else -> "$msg — tap for help"
        }
    }

    private fun phoneName(): String {
        val a = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        return try { a?.name } catch (_: SecurityException) { null } ?: "this phone"
    }

    /**
     * How to connect, in the terms of an ordinary Bluetooth mouse. The one
     * thing that differs from pairing any other mouse is that this app has to
     * be open while the computer pairs, so the phone is advertising a mouse at
     * the moment the computer reads its service list — a list it reads once
     * and caches for good.
     */
    private fun showConnectHelp() {
        if (isFinishing) return
        if (mouse == null) {
            if (bluetoothPermissionDenied()) showPermissionHelp() else requestNeededPermissions()
            return
        }
        when (lastState) {
            HidMouse.State.UNSUPPORTED -> { showUnsupportedHelp(); return }
            HidMouse.State.OFF -> { showBluetoothOffHelp(); return }
            else -> {}
        }
        if (lastState == HidMouse.State.CONNECTED) {
            AlertDialog.Builder(this)
                .setTitle("Connected to $lastMsg")
                .setMessage(
                    "The phone is working as a mouse. To switch computers, disconnect " +
                            "it from $lastMsg's Bluetooth settings, then connect from the other one."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val phone = phoneName()
        val stale = mouse?.lastFailedHost?.let {
            val name = try { it.name } catch (_: SecurityException) { null } ?: it.address
            "$name is already paired, but it was paired while this app wasn't running, " +
                    "so it doesn't know the phone can be a mouse. Remove \"$phone\" from " +
                    "$name's Bluetooth settings (and $name from this phone's), then pair again:\n\n"
        } ?: ""

        AlertDialog.Builder(this)
            .setTitle("Connect to your computer")
            .setMessage(
                stale +
                        "Pair it like any Bluetooth mouse:\n\n" +
                        "1.  Keep this app open.\n" +
                        "2.  On the computer, open Bluetooth settings → Add device.\n" +
                        "3.  Pick \"$phone\" and confirm the code on both screens.\n\n" +
                        "The mouse connects on its own. After that, just open this app " +
                        "and it reconnects.\n\n" +
                        "Computer can't see the phone? Tap Make visible."
            )
            .setPositiveButton("Make visible") { _, _ ->
                try {
                    startActivity(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    )
                } catch (_: Exception) {
                }
            }
            .setNeutralButton("Phone Bluetooth") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun bluetoothPermissionDenied() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED

    /**
     * Once the system has stopped showing the prompt ("don't ask again", or two
     * denials on Android 11+), asking again does nothing visible — the only
     * way back is the app's own settings page.
     */
    private fun showPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth permission needed")
            .setMessage(
                "Gesture Mouse needs the Nearby devices permission to act as a " +
                        "Bluetooth mouse. Allow it in the app's settings, then come back."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                    )
                } catch (_: Exception) {
                }
            }
            .setNeutralButton("Ask again") { _, _ -> requestNeededPermissions() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBluetoothOffHelp() {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth is off")
            .setMessage("Turn Bluetooth on and the mouse starts by itself.")
            .setPositiveButton("Turn on") { _, _ ->
                try {
                    @Suppress("DEPRECATION")
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Acting as a Bluetooth mouse needs the phone's HID device role. It's part
     * of Android since 9, but manufacturers can leave it out, and only one app
     * at a time can hold it — the second cause is far more common in practice.
     */
    private fun showUnsupportedHelp() {
        AlertDialog.Builder(this)
            .setTitle("The mouse couldn't start")
            .setMessage(
                "$lastMsg.\n\n" +
                        "•  Another app may already be using this phone as a Bluetooth " +
                        "keyboard or mouse. Close it (or force-stop it), then reopen " +
                        "Gesture Mouse.\n\n" +
                        "•  Otherwise, this phone's maker may have left out the Bluetooth " +
                        "feature that lets a phone act as a mouse. Restarting the phone " +
                        "is worth one try; if it still fails, this phone can't be used."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mouse?.stop()
        mouse = null
    }
}
