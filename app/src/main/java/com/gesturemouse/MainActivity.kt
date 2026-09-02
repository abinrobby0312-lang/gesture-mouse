package com.gesturemouse

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

        b.pairButton.setOnClickListener { onPairPressed() }

        android.util.Log.i(HidMouse.TAG, "MainActivity.onCreate")
        requestNeededPermissions()
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
            wanted += Manifest.permission.BLUETOOTH_SCAN
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

    private fun showState(state: HidMouse.State, msg: String) {
        val color = when (state) {
            HidMouse.State.CONNECTED -> Color.parseColor("#4FE0C4")
            HidMouse.State.WAITING, HidMouse.State.REGISTERING -> Color.parseColor("#FFC24B")
            else -> Color.parseColor("#FF5C6E")
        }
        (b.statusDot.background as? GradientDrawable)?.setColor(color)
        b.statusText.text = when (state) {
            HidMouse.State.CONNECTED -> "Connected to $msg"
            else -> msg
        }
        b.pairButton.text = if (state == HidMouse.State.CONNECTED) "Hosts" else "Pair"
    }

    /**
     * Scans, pairs and connects in one place. Pairing has to happen while the
     * HID service is advertising — a host reads a device's service list once,
     * when bonding, so anything paired beforehand has no mouse in it.
     */
    private fun onPairPressed() {
        val m = mouse ?: run {
            requestNeededPermissions()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            return
        }
        DevicePicker(this, m).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mouse?.stop()
        mouse = null
    }
}
