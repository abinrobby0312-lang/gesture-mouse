package com.gesturemouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Finds a computer to be a mouse for.
 *
 * Listing only bonded devices wasn't enough: pairing has to happen *while* the
 * HID service is advertising, otherwise the host caches a service list with no
 * mouse in it and never offers one. So this scans, bonds, and connects in one
 * flow, with the HID app already registered the whole time.
 */
@SuppressLint("MissingPermission")
class DevicePicker(
    private val activity: AppCompatActivity,
    private val mouse: HidMouse
) {
    private val adapter: BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val devices = mutableListOf<BluetoothDevice>()
    private val rows = mutableListOf<String>()
    private var listAdapter: ArrayAdapter<String>? = null
    private var dialog: AlertDialog? = null
    private var pendingBond: BluetoothDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) add(d)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    dialog?.setTitle("Select a computer")
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    Log.i(HidMouse.TAG, "bond state for ${d?.address} = $state")
                    if (d == null || d.address != pendingBond?.address) return
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            // bonded with the HID service live, so the host has
                            // a mouse in its service list — now push the
                            // connection, once the stack has stopped moving
                            Log.i(HidMouse.TAG, "bonded, connecting HID to ${d.address}")
                            mouse.connectAfterBond(d)
                            pendingBond = null
                            dismiss()
                        }

                        BluetoothDevice.BOND_NONE -> {
                            // BONDING -> NONE is a refused, cancelled or failed
                            // pairing. Leaving the dialog sitting on "Pairing…"
                            // made that look identical to one still in flight.
                            Log.w(HidMouse.TAG, "pairing with ${d.address} failed")
                            pendingBond = null
                            dialog?.setTitle("Pairing failed — tap to try again")
                        }
                    }
                }
            }
        }
    }

    private fun label(d: BluetoothDevice): String {
        val name = try { d.name } catch (e: SecurityException) { null } ?: d.address
        val bonded = try { d.bondState == BluetoothDevice.BOND_BONDED } catch (e: Exception) { false }
        return if (bonded) "$name  ·  paired" else name
    }

    private fun add(d: BluetoothDevice) {
        if (devices.any { it.address == d.address }) return
        devices += d
        rows += label(d)
        listAdapter?.notifyDataSetChanged()
    }

    fun show() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            AlertDialog.Builder(activity)
                .setTitle("Bluetooth is off")
                .setMessage("Turn Bluetooth on, then try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        devices.clear()
        rows.clear()
        try { a.bondedDevices?.forEach { add(it) } } catch (_: SecurityException) {}

        listAdapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, rows)

        activity.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })

        dialog = AlertDialog.Builder(activity)
            .setTitle("Scanning…")
            .setAdapter(listAdapter) { _, which -> choose(devices[which]) }
            .setNeutralButton("Make phone visible", null)
            .setNegativeButton("Close", null)
            .setOnDismissListener { cleanup() }
            .create()

        dialog?.show()

        // keep the dialog open after tapping "Make phone visible"
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            activity.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            )
        }

        try {
            if (a.isDiscovering) a.cancelDiscovery()
            val started = a.startDiscovery()
            Log.i(HidMouse.TAG, "startDiscovery returned $started")
            if (!started) dialog?.setTitle("Select a computer")
        } catch (e: SecurityException) {
            Log.e(HidMouse.TAG, "scan permission missing", e)
            dialog?.setTitle("Scan permission missing")
        }
    }

    private fun choose(d: BluetoothDevice) {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}

        val bonded = try { d.bondState == BluetoothDevice.BOND_BONDED } catch (e: Exception) { false }
        if (bonded) {
            Log.i(HidMouse.TAG, "already bonded, connecting HID to ${d.address}")
            mouse.connectTo(d)
            dismiss()
        } else {
            Log.i(HidMouse.TAG, "createBond with ${d.address}")
            pendingBond = d
            dialog?.setTitle("Pairing… confirm on both screens")
            try { d.createBond() } catch (e: Exception) {
                Log.e(HidMouse.TAG, "createBond threw", e)
            }
        }
    }

    private fun dismiss() {
        try { dialog?.dismiss() } catch (_: Exception) {}
    }

    private fun cleanup() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}
        dialog = null
    }
}
