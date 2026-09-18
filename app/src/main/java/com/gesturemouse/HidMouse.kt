package com.gesturemouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sign

/**
 * Presents the phone to the world as an ordinary Bluetooth mouse.
 *
 * The whole point: the host doesn't need to know this app exists. It pairs with
 * a standard HID mouse, so Windows, macOS, Linux, iPadOS and most smart TVs all
 * drive it with the generic driver they already ship. Nothing to install.
 *
 * Reports are relative (dx, dy), never absolute, which is also why this works
 * without ever knowing the host's screen size.
 *
 * ## Connecting
 *
 * Getting registered is not the same as getting connected, and the gap between
 * the two is where this used to fail. `connect()` is a request, not a result:
 * it can return false outright, or return true and still land back in
 * DISCONNECTED a few seconds later. A single un-checked call therefore looks
 * exactly like success while nothing happens.
 *
 * So every connection now goes through one place — [startAttempts] — which
 * checks the return value, arms a timeout, retries with backoff, and stops with
 * a diagnosis rather than in silence. See [onAttemptFailed] for what "gave up"
 * reports and why.
 *
 * Who connects, and when:
 * - First time: the user pairs from the computer's Bluetooth settings while
 *   the app is open. The computer opens the mouse connection itself; the
 *   phone only asks if it hasn't after [HOST_FIRST_MS] ([connectAfterBond]).
 * - After that: opening the app reconnects to the remembered host
 *   ([autoReconnect]), and so does a dropped link.
 * - Never: guessing across other paired computers. Old pairings refuse the
 *   mouse, and working through them holds the phone's single HID slot —
 *   which is exactly what stopped the first real pairing from connecting.
 *
 * The HID registration only lives while the app is in the foreground; the
 * stack drops it when the app is backgrounded, and [ensureStarted] brings it
 * back on the next resume.
 */
@SuppressLint("MissingPermission")
class HidMouse(private val context: Context) {

    companion object {
        const val TAG = "GMouse"
        private const val REPORT_ID = 2
        private const val KEYBOARD_REPORT_ID = 1

        /**
         * Gap after each keyboard report. Reports sent back to back can be
         * coalesced or dropped on the way, and a lost key-up turns into a key
         * that repeats until the next report — so every stroke is a separate
         * press and release with a little air between them.
         */
        private const val KEY_GAP_MS = 8L

        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 2
        const val BUTTON_MIDDLE = 4

        /** How many times to ask for one connection before giving up. */
        private const val MAX_ATTEMPTS = 4

        /** How many times to wait on the HID service before calling it dead. */
        private const val MAX_DEFERRALS = 8

        /**
         * How long a single attempt gets before it counts as failed.
         *
         * A host that is going to accept usually does so in well under two
         * seconds; one that is going to refuse often says nothing at all, which
         * is why this needs a timeout rather than waiting on a callback.
         */
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /** Gap before each retry. The last value repeats if attempts outrun it. */
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        /**
         * After a fresh bond, how long the computer gets to open the mouse
         * connection itself before the phone asks. Windows does this on its
         * own — on a Pixel 10a / Windows host it connected before the phone
         * had even reported the bond — and refused phone-initiated connects
         * made while it was still setting up ("L2CAP connection rejected,
         * reason=0x4", no resources).
         */
        private const val HOST_FIRST_MS = 10_000L

        private const val PREFS = "gesturemouse"
        private const val KEY_LAST_HOST = "lastHost"

        /**
         * A keyboard (report ID 1) and a boot mouse plus wheel (report ID 2).
         *
         * Mouse reports: [buttons, dx, dy, wheel] — one byte each, with
         * dx/dy/wheel signed and relative. Three button bits then five bits of
         * padding, because HID fields have to land on byte boundaries.
         *
         * Changing this descriptor changes the SDP record, which a host caches
         * at pairing: every computer paired to an older version has to be
         * re-paired before it sees the change.
         */
        private val DESCRIPTOR = byteArrayOf(
            // ---- keyboard, report ID 1 ----
            // The boot-keyboard layout: [modifiers, reserved, key1..key6]. It
            // is also exactly what a host in boot protocol expects, so the same
            // report serves both modes. The LED output report carries nothing
            // the phone uses, but hosts that set Caps Lock expect to be able to.
            0x05, 0x01,                          // Usage Page (Generic Desktop)
            0x09, 0x06,                          // Usage (Keyboard)
            0xA1.toByte(), 0x01,                 // Collection (Application)
            0x85.toByte(), KEYBOARD_REPORT_ID.toByte(), // Report ID (1)
            0x05, 0x07,                          //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0.toByte(),                 //   Usage Minimum (Left Control)
            0x29, 0xE7.toByte(),                 //   Usage Maximum (Right GUI)
            0x15, 0x00,                          //   Logical Minimum (0)
            0x25, 0x01,                          //   Logical Maximum (1)
            0x75, 0x01,                          //   Report Size (1)
            0x95.toByte(), 0x08,                 //   Report Count (8)
            0x81.toByte(), 0x02,                 //   Input (Data, Var, Abs) — modifiers
            0x95.toByte(), 0x01,                 //   Report Count (1)
            0x75, 0x08,                          //   Report Size (8)
            0x81.toByte(), 0x01,                 //   Input (Const) — reserved
            0x95.toByte(), 0x05,                 //   Report Count (5)
            0x75, 0x01,                          //   Report Size (1)
            0x05, 0x08,                          //   Usage Page (LEDs)
            0x19, 0x01,                          //   Usage Minimum (Num Lock)
            0x29, 0x05,                          //   Usage Maximum (Kana)
            0x91.toByte(), 0x02,                 //   Output (Data, Var, Abs) — LEDs
            0x95.toByte(), 0x01,                 //   Report Count (1)
            0x75, 0x03,                          //   Report Size (3)
            0x91.toByte(), 0x01,                 //   Output (Const) — padding
            0x95.toByte(), 0x06,                 //   Report Count (6)
            0x75, 0x08,                          //   Report Size (8)
            0x15, 0x00,                          //   Logical Minimum (0)
            0x25, 0x65,                          //   Logical Maximum (101)
            0x05, 0x07,                          //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,                          //   Usage Minimum (0)
            0x29, 0x65,                          //   Usage Maximum (101)
            0x81.toByte(), 0x00,                 //   Input (Data, Array) — keys
            0xC0.toByte(),                       // End Collection

            // ---- mouse, report ID 2 ----
            0x05, 0x01,                          // Usage Page (Generic Desktop)
            0x09, 0x02,                          // Usage (Mouse)
            0xA1.toByte(), 0x01,                 // Collection (Application)
            0x85.toByte(), REPORT_ID.toByte(),   //   Report ID (2)
            0x09, 0x01,                          //   Usage (Pointer)
            0xA1.toByte(), 0x00,                 //   Collection (Physical)
            0x05, 0x09,                          //     Usage Page (Button)
            0x19, 0x01,                          //     Usage Minimum (Button 1)
            0x29, 0x03,                          //     Usage Maximum (Button 3)
            0x15, 0x00,                          //     Logical Minimum (0)
            0x25, 0x01,                          //     Logical Maximum (1)
            0x75, 0x01,                          //     Report Size (1 bit)
            0x95.toByte(), 0x03,                 //     Report Count (3)
            0x81.toByte(), 0x02,                 //     Input (Data, Var, Abs)
            0x75, 0x05,                          //     Report Size (5 bits)
            0x95.toByte(), 0x01,                 //     Report Count (1)
            0x81.toByte(), 0x01,                 //     Input (Const) — padding
            0x05, 0x01,                          //     Usage Page (Generic Desktop)
            0x09, 0x30,                          //     Usage (X)
            0x09, 0x31,                          //     Usage (Y)
            0x09, 0x38,                          //     Usage (Wheel)
            0x15, 0x81.toByte(),                 //     Logical Minimum (-127)
            0x25, 0x7F,                          //     Logical Maximum (127)
            0x75, 0x08,                          //     Report Size (8 bits)
            0x95.toByte(), 0x03,                 //     Report Count (3)
            0x81.toByte(), 0x06,                 //     Input (Data, Var, Rel)
            0xC0.toByte(),                       //   End Collection
            0xC0.toByte()                        // End Collection
        )
    }

    enum class State { UNSUPPORTED, OFF, REGISTERING, WAITING, CONNECTING, STALE_BOND, CONNECTED }

    var onState: ((State, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var proxy: BluetoothHidDevice? = null

    /** Read from gesture threads in [send], written from the profile callback. */
    @Volatile private var host: BluetoothDevice? = null
    private var buttons = 0

    /**
     * A getProfileProxy call is in flight.
     *
     * onCreate and onResume both reach start(), and the proxy arrives
     * asynchronously — so without this the second call runs while [proxy] is
     * still null, asks the stack for a second proxy, and both callbacks then
     * race to registerApp(). The loser logs "registerApp returned false" and
     * the registration flaps between true and false, dropping the HID service
     * at exactly the moment a host is trying to connect.
     *
     * Every path that leaves the in-flight state has to clear this, including
     * the failure paths — a latched `true` makes [start] return early forever
     * and the only cure is a force-stop.
     */
    @Volatile private var proxyPending = false

    /**
     * Whether the host has put us in boot protocol.
     *
     * Boot protocol is a fixed 3-byte report — buttons, dx, dy — with no report
     * ID and no wheel. macOS routinely selects it during connection setup;
     * Windows generally leaves the device in report protocol. Keep sending
     * report-protocol packets to a host that asked for boot and the pointer
     * either sits still or goes haywire, because the bytes land in the wrong
     * fields.
     */
    @Volatile private var bootProtocol = false

    /**
     * Whether the HID app is currently registered with the Bluetooth stack.
     *
     * The stack can drop the registration on its own — it does so when a host
     * disconnects — without the activity ever being destroyed. Tracking only
     * "do we have a HidMouse object" was not enough: the object outlives its
     * registration and the app sits there dead, still claiming to be ready.
     */
    @Volatile var registered = false
        private set

    // ---- connection attempt state. main thread only. ----

    /** The device we are currently trying to reach, or null if we aren't. */
    private var pendingTarget: BluetoothDevice? = null

    /**
     * The bonded device we last ran out of attempts on.
     *
     * Kept so the UI can offer to drop the pairing and make it again, which is
     * the only thing that clears a host's cached service list.
     */
    @Volatile var lastFailedHost: BluetoothDevice? = null
        private set

    /** Attempts spent on [pendingTarget] so far. */
    private var attempt = 0

    /**
     * Times we've put an attempt off waiting for the HID service to come up.
     *
     * Bounded because deferring doesn't spend an attempt: if registration never
     * completes, an unbounded wait is a retry loop that runs for as long as the
     * app is open and never reports anything.
     */
    private var deferrals = 0

    /**
     * Set when the host explicitly unplugs the virtual cable.
     *
     * That is the host saying "I don't want this mouse", which is different
     * from a link that dropped. Reconnecting over the top of it would fight the
     * user, so auto-reconnect sits out until something asks explicitly.
     */
    @Volatile private var unplugged = false

    // sub-pixel remainder: a report can only carry whole units, so fractional
    // movement would otherwise be truncated away and slow drags would stall
    private var remX = 0f
    private var remY = 0f

    val isConnected: Boolean get() = host != null

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun report(state: State, msg: String) {
        Log.i(TAG, "state=$state msg=$msg")
        main.post { onState?.invoke(state, msg) }
    }

    private fun nameOf(d: BluetoothDevice?): String =
        try { d?.name } catch (_: SecurityException) { null } ?: d?.address ?: "host"

    // ------------------------------------------------------------------
    // registration
    // ------------------------------------------------------------------

    /**
     * Bring the HID service back up if it isn't live, and get back to a host if
     * we know one. Safe to call repeatedly — this is what every onResume goes
     * through, so the app self-heals after the stack drops us instead of
     * needing a force-stop.
     */
    fun ensureStarted() {
        if (host != null) return
        if (adapter?.isEnabled != true) {
            // start() owns the "no adapter" / "off" reporting and the receiver
            // that notices Bluetooth coming back
            start()
            return
        }
        val p = proxy
        when {
            registered -> {
                // Registered but with nobody on the other end. This used to
                // stop here, which is why reopening the app never reconnected:
                // the only connect() in the whole class lived in the
                // registration callback, and that fires once per registration.
                Log.i(TAG, "ensureStarted: registered with no host — trying to reconnect")
                // reopening the app is the user asking again, so computers that
                // refused earlier get another go
                if (pendingTarget == null) refused.clear()
                autoReconnect("resume")
            }
            p != null -> {
                Log.i(TAG, "ensureStarted: have proxy but not registered — re-registering")
                registerApp()
            }
            else -> {
                Log.i(TAG, "ensureStarted: no proxy — starting from scratch")
                start()
            }
        }
    }

    fun start() {
        val a = adapter
        if (a == null) {
            Log.e(TAG, "no BluetoothAdapter on this device")
            report(State.UNSUPPORTED, "No Bluetooth adapter")
            return
        }
        // before the enabled check: with Bluetooth off, this is what hears it
        // come back on
        if (!bondReceiverRegistered) {
            ContextCompat.registerReceiver(
                context, bondReceiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            bondReceiverRegistered = true
        }
        if (!a.isEnabled) {
            Log.w(TAG, "adapter present but disabled")
            report(State.OFF, "Bluetooth is off")
            return
        }
        if (proxyPending) {
            Log.i(TAG, "start: a proxy request is already in flight — not asking twice")
            return
        }
        report(State.REGISTERING, "Starting…")
        proxyPending = true
        val asked = try {
            a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                    Log.i(TAG, "onServiceConnected profile=$profile (HID_DEVICE=${BluetoothProfile.HID_DEVICE})")
                    // clear before the profile check: returning early with this
                    // still set latches start() shut for the rest of the run
                    proxyPending = false
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    if (proxy != null) {
                        // a duplicate proxy from an earlier race; registering
                        // against it would unregister the live one
                        Log.w(TAG, "onServiceConnected: already have a proxy, ignoring the spare")
                        return
                    }
                    proxy = service as BluetoothHidDevice
                    registerApp()
                }

                override fun onServiceDisconnected(profile: Int) {
                    Log.w(TAG, "onServiceDisconnected profile=$profile")
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        proxyPending = false
                        proxy = null
                        host = null
                        registered = false
                        main.post { stopTrying() }
                        report(State.OFF, "HID service disconnected")
                    }
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            proxyPending = false
            Log.e(TAG, "getProfileProxy threw", e)
            report(State.UNSUPPORTED, "HID unavailable: ${e.message}")
            return
        }
        Log.i(TAG, "getProfileProxy(HID_DEVICE) returned $asked")
        if (!asked) {
            proxyPending = false
            report(State.UNSUPPORTED, "This phone has no Bluetooth HID device role")
        }
    }

    private fun registerApp() {
        if (registered) {
            // re-registering a live app makes the stack tear the first one
            // down, which looks to a connecting host like the device vanished
            Log.i(TAG, "registerApp: already registered, skipping")
            return
        }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Gesture Mouse",
            "Phone as an air trackpad and keyboard",
            "GestureMouse",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DESCRIPTOR
        )

        val ok = try {
            // Both QoS records are null on purpose: that means "no QoS
            // preference", and the stack falls back to the L2CAP defaults the
            // host proposes. An explicit record has to be one the host will
            // actually agree to, and a mismatch is refused during channel
            // setup — which surfaces as a connection that never completes
            // rather than as an error anyone can see.
            proxy?.registerApp(sdp, null, null, executor, object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                Log.i(TAG, "onAppStatusChanged registered=$registered plugged=${pluggedDevice?.address}")
                this@HidMouse.registered = registered
                if (registered) {
                    report(State.WAITING, "Not connected")
                    val known = pluggedDevice ?: proxy?.getDevicesMatchingConnectionStates(
                        intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
                    )?.firstOrNull()
                    main.post {
                        if (known != null) startAttempts(known, "plugged") else autoReconnect("registered")
                    }
                } else {
                    host = null
                    main.post { stopTrying() }
                    report(State.OFF, "HID app unregistered")
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                Log.i(TAG, "onConnectionStateChanged device=${device?.address} state=$state")
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        remX = 0f; remY = 0f
                        bootProtocol = false   // until the host says otherwise
                        unplugged = false
                        lastFailedHost = null
                        if (device != null) remember(device)
                        main.post { stopTrying() }
                        report(State.CONNECTED, nameOf(device))
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        // a computer opening the connection itself. The HID
                        // role holds one connection at a time, so an attempt
                        // of ours to some other device would get it refused.
                        val ours = pendingTarget
                        if (device != null && ours != null && ours.address != device.address) {
                            Log.i(TAG, "incoming from ${device.address} — dropping our attempt on ${ours.address}")
                            main.post { stopTrying() }
                        }
                        report(State.CONNECTING, "Connecting to ${nameOf(device)}…")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasHost = device != null && device == host
                        if (wasHost) host = null
                        bootProtocol = false
                        main.post { onDisconnected(device, wasHost) }
                    }
                }
            }

            override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
                bootProtocol = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
                Log.i(TAG, "onSetProtocol boot=$bootProtocol")
            }

            /**
             * Hosts poll for an initial report during setup. macOS in
             * particular will not finish bringing the pointer up until it gets
             * an answer; leaving this unimplemented meant the connection sat
             * half-open until it timed out.
             */
            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                val d = device ?: return
                val body = when {
                    id.toInt() == KEYBOARD_REPORT_ID -> ByteArray(8)   // no keys down
                    bootProtocol -> byteArrayOf(buttons.toByte(), 0, 0)
                    else -> byteArrayOf(buttons.toByte(), 0, 0, 0)
                }
                Log.i(TAG, "onGetReport type=$type id=$id -> replying ${body.size} bytes")
                proxy?.replyReport(d, type, id, body)
            }

            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
                // nothing to configure on a mouse, but the host expects an ack
                proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice?) {
                Log.i(TAG, "onVirtualCableUnplug ${device?.address}")
                if (device == host) host = null
                bootProtocol = false
                unplugged = true
                main.post { stopTrying() }
                report(State.WAITING, "Host disconnected the mouse")
            }
            })
        } catch (e: Exception) {
            Log.e(TAG, "registerApp threw", e)
            report(State.UNSUPPORTED, "HID registration failed: ${e.message}")
            return
        } ?: false

        Log.i(TAG, "registerApp returned $ok")
        // Android lets exactly one app hold the HID device role. False here is
        // usually another keyboard/mouse app holding it, not a missing feature.
        if (!ok) report(State.UNSUPPORTED, "Couldn't start the mouse service")
    }

    fun stop() {
        main.post { stopTrying() }
        if (bondReceiverRegistered) {
            try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
            bondReceiverRegistered = false
        }
        try {
            releaseButtons()
            proxy?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        } catch (_: Exception) {
        }
        proxy = null
        host = null
        registered = false
        // without this a later start() sees an in-flight request that will
        // never arrive and returns early forever
        proxyPending = false
    }

    // ------------------------------------------------------------------
    // connecting
    // ------------------------------------------------------------------

    private fun bondedHosts(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList().orEmpty() } catch (_: SecurityException) { emptyList() }

    /** An attempt is outstanding and hasn't been counted as failed yet. */
    private var awaiting = false

    /**
     * A computer has just paired with us. Give it [HOST_FIRST_MS] to open the
     * mouse connection itself, then ask from this end. Nothing else may use
     * the HID slot in that window — [pairing] holds auto-connect off.
     */
    private fun connectAfterBond(device: BluetoothDevice) {
        refused.remove(device.address)
        stopTrying()
        pairing = true
        Log.i(TAG, "connectAfterBond ${device.address}: leaving it to the host for ${HOST_FIRST_MS}ms")
        report(State.CONNECTING, "Paired — connecting…")
        main.removeCallbacks(postBondRunnable)
        postBondTarget = device
        main.postDelayed(postBondRunnable, HOST_FIRST_MS)
    }

    private var postBondTarget: BluetoothDevice? = null
    private val postBondRunnable = Runnable {
        pairing = false
        val d = postBondTarget ?: return@Runnable
        postBondTarget = null
        if (host != null) return@Runnable
        stopTrying()
        startAttempts(d, "post-bond")
    }

    /**
     * A pairing is under way or has just finished. Auto-connect stays out of
     * the way until it resolves: the pairing prompt pauses the activity, and
     * the onResume after it used to launch a reconnect straight across the
     * pairing, holding the HID slot while the new computer tried to get in.
     */
    private var pairing = false

    /** Something is pairing with the phone: free the HID slot for it. */
    private fun onBonding(device: BluetoothDevice) {
        Log.i(TAG, "${device.address} is pairing — standing down auto-connect")
        pairing = true
        stopTrying()
    }

    /**
     * A pairing that isn't ours to follow up: it failed, was cancelled, or
     * bonded something that isn't a computer. Auto-connect may resume.
     */
    private fun onBondFailed(device: BluetoothDevice) {
        if (postBondTarget != null) return
        Log.i(TAG, "${device.address} pairing ended, nothing to connect")
        pairing = false
    }

    /**
     * Devices that ran out of attempts this session. Auto-connect skips them,
     * because retrying a computer that refuses the mouse does nothing but hold
     * the HID slot while a working one is trying to get in.
     */
    private val refused = mutableSetOf<String>()

    private val timeoutRunnable = Runnable { onAttemptFailed("timed out") }
    private val retryRunnable = Runnable { fireAttempt() }

    private fun startAttempts(device: BluetoothDevice, why: String) {
        cancelAttempts()
        unplugged = false
        pendingTarget = device
        attempt = 0
        deferrals = 0
        Log.i(TAG, "startAttempts($why) target=${device.address}")
        fireAttempt()
    }

    private fun fireAttempt() {
        val d = pendingTarget ?: return
        if (host != null) { cancelAttempts(); return }

        val p = proxy
        if (p == null || !registered) {
            // nothing to connect *with* yet. don't burn an attempt on it —
            // registration will call back round to autoReconnect().
            if (deferrals >= MAX_DEFERRALS) {
                Log.w(TAG, "fireAttempt: HID service never came up, giving up")
                cancelAttempts()
                report(State.OFF, "The HID service didn't start — reopen the app")
                return
            }
            deferrals++
            Log.w(TAG, "fireAttempt: not registered yet (proxy=${p != null}) — deferring $deferrals/$MAX_DEFERRALS")
            report(State.CONNECTING, "Waiting for the HID service…")
            main.postDelayed(retryRunnable, BACKOFF_MS[0])
            return
        }

        attempt++
        // discovery starves the link; an active scan is a common reason a
        // connect that should work doesn't
        val a = adapter
        try { if (a != null && a.isDiscovering) a.cancelDiscovery() } catch (_: Exception) {}

        val asked = try { p.connect(d) } catch (e: Exception) {
            Log.e(TAG, "connect threw", e); false
        }
        awaiting = true
        Log.i(TAG, "connect attempt $attempt/$MAX_ATTEMPTS to ${d.address} returned $asked")
        report(State.CONNECTING, "Connecting to ${nameOf(d)}… ($attempt/$MAX_ATTEMPTS)")

        if (!asked) {
            // refused outright — no state change is coming, so don't sit on the
            // full timeout waiting for one
            main.post { onAttemptFailed("connect() refused") }
            return
        }
        main.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    private fun onAttemptFailed(why: String) {
        // the stack delivers DISCONNECTED twice for one failed link; without
        // this each copy spent an attempt and queued its own retry
        if (!awaiting) {
            Log.i(TAG, "ignoring duplicate failure ($why)")
            return
        }
        awaiting = false
        main.removeCallbacks(timeoutRunnable)
        if (host != null) { cancelAttempts(); return }
        val d = pendingTarget ?: return
        Log.w(TAG, "attempt $attempt to ${d.address} failed: $why")

        if (attempt < MAX_ATTEMPTS) {
            val delay = BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.size - 1).coerceAtLeast(0)]
            Log.i(TAG, "retrying in ${delay}ms")
            main.postDelayed(retryRunnable, delay)
            return
        }

        // Out of attempts. The *reason* matters more than the failure: a host
        // we are bonded to that still won't take the mouse is the cached-SDP
        // case — it caches a device's service list when it bonds, and if the
        // HID service wasn't up at that moment it never learns about it. No
        // amount of retrying fixes that; re-pairing does.
        val bonded = try { d.bondState == BluetoothDevice.BOND_BONDED } catch (_: Exception) { false }
        pendingTarget = null
        lastFailedHost = if (bonded) d else null
        refused += d.address
        release(d)

        if (bonded) {
            Log.w(TAG, "giving up on ${d.address} — bonded but won't accept HID (stale service list?)")
            report(
                State.STALE_BOND,
                "${nameOf(d)} is paired but won't accept the mouse"
            )
        } else {
            report(State.WAITING, "Not connected")
        }
    }

    /** Abandon the current target and withdraw its request from the stack. */
    private fun stopTrying() {
        pendingTarget?.let { release(it) }
        cancelAttempts()
    }

    /**
     * Withdraw an outgoing request that may still be in flight. Dropping our
     * own bookkeeping isn't enough: the stack keeps working on it, and while
     * it does the single HID slot is busy — the next connect, ours or a
     * computer's, is refused with "connection already in progress".
     */
    private fun release(d: BluetoothDevice) {
        if (host?.address == d.address) return
        try { proxy?.disconnect(d) } catch (_: Exception) {}
    }

    private fun cancelAttempts() {
        main.removeCallbacks(timeoutRunnable)
        main.removeCallbacks(retryRunnable)
        pendingTarget = null
        awaiting = false
        attempt = 0
        deferrals = 0
    }

    /** Runs on the main thread, from the connection callback. */
    private fun onDisconnected(device: BluetoothDevice?, wasHost: Boolean) {
        val target = pendingTarget
        if (target != null && device?.address == target.address) {
            // the link we were mid-way through asking for just died. fail this
            // attempt now instead of waiting out the timeout.
            onAttemptFailed("host dropped the link")
            return
        }
        if (wasHost && !unplugged) {
            report(State.WAITING, "Disconnected — reconnecting…")
            autoReconnect("dropped")
            return
        }
        // a late event for a device we've already moved on from; the attempt
        // in progress owns the status strip
        if (target != null || host != null) return
        report(State.WAITING, "Not connected")
    }

    /**
     * Get back to the computer the mouse last worked with, if it's still bonded.
     *
     * Deliberately only that one. Trying every paired computer looked
     * friendlier, but on real hardware every one of them was an old pairing
     * that refuses the mouse, and working through them held the phone's single
     * HID slot for most of a minute — including right across a new computer's
     * pairing, which is how the first real attempt to connect failed. A
     * computer that has never taken the mouse connects on pairing instead
     * ([connectAfterBond]).
     */
    private fun autoReconnect(why: String) {
        if (host != null || pendingTarget != null) return
        if (pairing) {
            Log.i(TAG, "autoReconnect($why): a pairing is in progress, staying out of its way")
            return
        }
        if (unplugged) {
            Log.i(TAG, "autoReconnect($why): host unplugged us, staying put")
            return
        }
        val last = prefs.getString(KEY_LAST_HOST, null)
        if (last == null) {
            Log.i(TAG, "autoReconnect($why): no remembered host")
            report(State.WAITING, "Not connected")
            return
        }
        val d = bondedHosts().firstOrNull { it.address == last }
        if (d == null) {
            Log.i(TAG, "autoReconnect($why): remembered host $last is no longer bonded")
            prefs.edit().remove(KEY_LAST_HOST).apply()
            report(State.WAITING, "Not connected")
            return
        }
        if (d.address in refused) {
            Log.i(TAG, "autoReconnect($why): ${d.address} refused earlier this session")
            return
        }
        Log.i(TAG, "autoReconnect($why) -> ${d.address}")
        startAttempts(d, "auto:$why")
    }

    private fun isComputer(d: BluetoothDevice): Boolean = try {
        d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
    } catch (_: Exception) { false }

    /**
     * A computer that pairs with the phone while the app is open normally
     * opens the mouse connection itself. This is the fallback for one that
     * doesn't: once the bond lands, ask for the connection from this end.
     */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                onAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                return
            }
            @Suppress("DEPRECATION")
            val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            Log.i(TAG, "bond state for ${d.address} = $state computer=${isComputer(d)}")
            // the class isn't reliable mid-pairing, so only the final "is this
            // a computer to connect to" decision looks at it
            main.post {
                when (state) {
                    BluetoothDevice.BOND_BONDING -> onBonding(d)
                    BluetoothDevice.BOND_BONDED -> when {
                        // the usual case with Windows: it opens the mouse
                        // connection before the phone has even reported the bond
                        host != null -> {
                            Log.i(TAG, "${d.address} bonded, already connected to ${host?.address}")
                            pairing = false
                        }
                        isComputer(d) -> connectAfterBond(d)
                        else -> onBondFailed(d)
                    }
                    BluetoothDevice.BOND_NONE -> onBondFailed(d)
                }
            }
        }
    }
    private var bondReceiverRegistered = false

    /**
     * Bluetooth switched off or on while the app is open. Without this, turning
     * Bluetooth on from the quick-settings shade left the app saying "off"
     * until it was reopened.
     */
    private fun onAdapterState(state: Int) {
        main.post {
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "adapter going off")
                    stopTrying()
                    host = null
                    registered = false
                    report(State.OFF, "Bluetooth is off")
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "adapter on — starting up")
                    ensureStarted()
                }
            }
        }
    }

    /** Name of the computer the app reconnects to on launch, or null. */
    val rememberedHostName: String?
        get() {
            val last = prefs.getString(KEY_LAST_HOST, null) ?: return null
            return bondedHosts().firstOrNull { it.address == last }?.let { nameOf(it) } ?: last
        }

    /**
     * Stop reconnecting to the remembered computer on launch — for moving the
     * mouse to a different one. The pairing itself stays; the next computer
     * that connects becomes the remembered one.
     */
    fun forgetRememberedHost() {
        Log.i(TAG, "forgetting remembered host")
        prefs.edit().remove(KEY_LAST_HOST).apply()
    }

    private fun remember(device: BluetoothDevice) {
        try { prefs.edit().putString(KEY_LAST_HOST, device.address).apply() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // reports
    // ------------------------------------------------------------------

    private fun send(dx: Int, dy: Int, wheel: Int): Boolean {
        val p = proxy ?: return false
        val d = host ?: return false
        val bx = dx.coerceIn(-127, 127).toByte()
        val by = dy.coerceIn(-127, 127).toByte()

        return if (bootProtocol) {
            // boot mouse: no report ID, exactly three bytes, no wheel field.
            // scrolling is simply unavailable in this mode by definition.
            p.sendReport(d, 0, byteArrayOf(buttons.toByte(), bx, by))
        } else {
            p.sendReport(
                d, REPORT_ID,
                byteArrayOf(buttons.toByte(), bx, by, wheel.coerceIn(-127, 127).toByte())
            )
        }
    }

    /**
     * Relative move. Carries the sub-pixel remainder so slow, precise movement
     * still creeps instead of rounding to nothing, and splits anything past the
     * single-report limit of 127 into several reports.
     */
    fun move(dxf: Float, dyf: Float) {
        if (host == null) return
        val tx = dxf + remX
        val ty = dyf + remY
        var ix = tx.toInt()
        var iy = ty.toInt()
        remX = tx - ix
        remY = ty - iy
        if (ix == 0 && iy == 0) return

        while (ix != 0 || iy != 0) {
            val stepX = if (abs(ix) > 127) 127 * ix.sign else ix
            val stepY = if (abs(iy) > 127) 127 * iy.sign else iy
            if (!send(stepX, stepY, 0)) return
            ix -= stepX
            iy -= stepY
        }
    }

    /** Reverse the wheel so content follows the fingers; see [Settings.naturalScroll]. */
    @Volatile var naturalScroll = false

    fun scroll(amount: Int) {
        if (host == null || amount == 0) return
        var left = if (naturalScroll) -amount else amount
        while (left != 0) {
            val step = if (abs(left) > 127) 127 * left.sign else left
            if (!send(0, 0, step)) return
            left -= step
        }
    }

    fun buttonDown(mask: Int) {
        if (buttons or mask == buttons) return
        buttons = buttons or mask
        send(0, 0, 0)
    }

    fun buttonUp(mask: Int) {
        if (buttons and mask == 0) return
        buttons = buttons and mask.inv()
        send(0, 0, 0)
    }

    fun click(mask: Int = BUTTON_LEFT) {
        buttonDown(mask)
        main.postDelayed({ buttonUp(mask) }, 40)
    }

    // ------------------------------------------------------------------
    // keyboard
    // ------------------------------------------------------------------

    /**
     * Keystrokes go out on their own thread, in order: a word from voice typing
     * is dozens of reports with a pause after each, which mustn't block the UI
     * or interleave with the next word.
     */
    private val typist = Executors.newSingleThreadExecutor()

    /** Type [text] on the computer. Characters with no US key are skipped. */
    fun type(text: CharSequence) {
        if (host == null || text.isEmpty()) return
        val strokes = KeyMap.strokes(text)
        // counts only — never what was typed
        Log.i(TAG, "keyboard: ${strokes.size} stroke(s), ${maxOf(0, text.length - strokes.size)} char(s) with no key")
        typist.execute { strokes.forEach { stroke(it.modifiers, it.usage) } }
    }

    /** Press and release one key, e.g. [KeyMap.BACKSPACE] [count] times. */
    fun key(usage: Int, count: Int = 1, modifiers: Int = 0) {
        if (host == null || count <= 0) return
        Log.i(TAG, "keyboard: key 0x${usage.toString(16)} x$count")
        typist.execute { repeat(count) { stroke(modifiers, usage) } }
    }

    /** Runs on [typist]. */
    private fun stroke(modifiers: Int, usage: Int) {
        val p = proxy ?: return
        val d = host ?: return
        try {
            val ok = p.sendReport(d, KEYBOARD_REPORT_ID, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
            if (!ok) Log.w(TAG, "keyboard: sendReport refused")
            Thread.sleep(KEY_GAP_MS)
            // always release, even for a repeated letter: "ll" is two strokes
            p.sendReport(d, KEYBOARD_REPORT_ID, ByteArray(8))
            Thread.sleep(KEY_GAP_MS)
        } catch (e: Exception) {
            Log.w(TAG, "keyboard report failed", e)
        }
    }

    fun releaseButtons() {
        if (buttons != 0) {
            buttons = 0
            send(0, 0, 0)
        }
    }

}
