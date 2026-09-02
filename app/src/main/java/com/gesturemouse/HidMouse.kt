package com.gesturemouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 */
@SuppressLint("MissingPermission")
class HidMouse(private val context: Context) {

    companion object {
        const val TAG = "GMouse"
        private const val REPORT_ID = 2
        private const val KEYBOARD_REPORT_ID = 1

        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 2
        const val BUTTON_MIDDLE = 4

        /** Milliseconds a key is held, and the gap before the next one. */
        private const val KEY_HOLD = 14L
        private const val KEY_GAP = 14L

        /**
         * Composite descriptor: a mouse *and* a keyboard, told apart by report
         * ID. The mouse keeps ID 2, exactly as it was before the keyboard
         * existed, so nothing about pointer behaviour changes.
         *
         * Mouse reports are [buttons, dx, dy, wheel] — one byte each, with
         * dx/dy/wheel signed and relative. Three button bits then five bits of
         * padding, because HID fields have to land on byte boundaries.
         *
         * Keyboard reports are the standard 8 bytes: [modifiers, reserved,
         * key1..key6]. Six slots is the convention even though we only ever
         * press one key at a time.
         *
         * NOTE: changing this descriptor invalidates every existing pairing. A
         * host reads the HID service record once, while bonding, and caches it
         * forever — a computer paired against the mouse-only version will never
         * see the keyboard. Those hosts must be unpaired on both sides and
         * paired again through the app.
         */
        private val DESCRIPTOR = byteArrayOf(
            // ---- keyboard ----
            0x05, 0x01,                                   // Usage Page (Generic Desktop)
            0x09, 0x06,                                   // Usage (Keyboard)
            0xA1.toByte(), 0x01,                          // Collection (Application)
            0x85.toByte(), KEYBOARD_REPORT_ID.toByte(),   //   Report ID (1)
            0x05, 0x07,                                   //   Usage Page (Keyboard)
            0x19, 0xE0.toByte(),                          //   Usage Minimum (LeftControl)
            0x29, 0xE7.toByte(),                          //   Usage Maximum (Right GUI)
            0x15, 0x00,                                   //   Logical Minimum (0)
            0x25, 0x01,                                   //   Logical Maximum (1)
            0x75, 0x01,                                   //   Report Size (1 bit)
            0x95.toByte(), 0x08,                          //   Report Count (8)
            0x81.toByte(), 0x02,                          //   Input (Data, Var, Abs) — modifiers
            0x95.toByte(), 0x01,                          //   Report Count (1)
            0x75, 0x08,                                   //   Report Size (8 bits)
            0x81.toByte(), 0x01,                          //   Input (Const) — reserved byte
            0x95.toByte(), 0x06,                          //   Report Count (6)
            0x75, 0x08,                                   //   Report Size (8 bits)
            0x15, 0x00,                                   //   Logical Minimum (0)
            0x25, 0x65,                                   //   Logical Maximum (101)
            0x05, 0x07,                                   //   Usage Page (Keyboard)
            0x19, 0x00,                                   //   Usage Minimum (0)
            0x29, 0x65,                                   //   Usage Maximum (101)
            0x81.toByte(), 0x00,                          //   Input (Data, Array) — 6 key slots
            0xC0.toByte(),                                // End Collection

            // ---- mouse ----
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

    enum class State { UNSUPPORTED, OFF, REGISTERING, WAITING, CONNECTED }

    var onState: ((State, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var proxy: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var buttons = 0

    // typing is a long sequence of sends and sleeps, so it gets its own thread
    // rather than blocking the HID callback executor
    private val keyExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var typing = false

    /**
     * A getProfileProxy call is in flight.
     *
     * onCreate and onResume both reach start(), and the proxy arrives
     * asynchronously — so without this the second call runs while [proxy] is
     * still null, asks the stack for a second proxy, and both callbacks then
     * race to registerApp(). The loser logs "registerApp returned false" and
     * the registration flaps between true and false, dropping the HID service
     * at exactly the moment a host is trying to connect.
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

    // sub-pixel remainder: a report can only carry whole units, so fractional
    // movement would otherwise be truncated away and slow drags would stall
    private var remX = 0f
    private var remY = 0f

    val isConnected: Boolean get() = host != null

    private fun report(state: State, msg: String) {
        Log.i(TAG, "state=$state msg=$msg")
        main.post { onState?.invoke(state, msg) }
    }

    /**
     * Bring the HID service back up if it isn't live. Safe to call repeatedly —
     * this is what every onResume goes through, so the app self-heals after the
     * stack drops us instead of needing a force-stop.
     */
    fun ensureStarted() {
        if (registered && host != null) return
        if (registered) {
            Log.i(TAG, "ensureStarted: registered, waiting for a host")
            return
        }
        val p = proxy
        if (p != null) {
            Log.i(TAG, "ensureStarted: have proxy but not registered — re-registering")
            registerApp()
        } else {
            Log.i(TAG, "ensureStarted: no proxy — starting from scratch")
            start()
        }
    }

    fun start() {
        val a = adapter
        if (a == null) {
            Log.e(TAG, "no BluetoothAdapter on this device")
            report(State.UNSUPPORTED, "No Bluetooth adapter")
            return
        }
        if (!a.isEnabled) {
            Log.w(TAG, "adapter present but disabled")
            report(State.OFF, "Bluetooth is off — turn it on")
            return
        }
        if (proxyPending) {
            Log.i(TAG, "start: a proxy request is already in flight — not asking twice")
            return
        }
        report(State.REGISTERING, "Registering HID service…")
        proxyPending = true
        val asked = try {
            a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                    Log.i(TAG, "onServiceConnected profile=$profile (HID_DEVICE=${BluetoothProfile.HID_DEVICE})")
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    proxyPending = false
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
            "Phone as an air trackpad",
            "GestureMouse",
            // COMBO, not MOUSE: the descriptor carries a keyboard too, and some
            // hosts use the subclass to decide what they're willing to route
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DESCRIPTOR
        )
        // explicit QoS. passing null lets Windows negotiate its own defaults,
        // but some stacks (macOS among them) treat an absent QoS record as a
        // reason to abandon the L2CAP setup.
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0,
            BluetoothHidDeviceAppQosSettings.MAX,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        val ok = try {
            proxy?.registerApp(sdp, null, qos, executor, object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                Log.i(TAG, "onAppStatusChanged registered=$registered plugged=${pluggedDevice?.address}")
                this@HidMouse.registered = registered
                if (registered) {
                    // if a host is already bonded and waiting, reconnect to it
                    val known = pluggedDevice ?: proxy?.getDevicesMatchingConnectionStates(
                        intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
                    )?.firstOrNull()
                    if (known != null) proxy?.connect(known)
                    report(State.WAITING, "Ready — pair \"Gesture Mouse\" from your computer")
                } else {
                    host = null
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
                        report(State.CONNECTED, device?.name ?: "Connected")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (device == host) host = null
                        bootProtocol = false
                        report(State.WAITING, "Disconnected — waiting for a host")
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
                    // a composite device gets polled for either report; answering
                    // a keyboard poll with a mouse-shaped reply confuses the host
                    id.toInt() == KEYBOARD_REPORT_ID -> ByteArray(8)
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
                report(State.WAITING, "Host disconnected the mouse")
            }
            })
        } catch (e: Exception) {
            Log.e(TAG, "registerApp threw", e)
            report(State.UNSUPPORTED, "HID registration failed: ${e.message}")
            return
        } ?: false

        Log.i(TAG, "registerApp returned $ok")
        if (!ok) report(State.UNSUPPORTED, "This phone refused the HID registration")
    }

    fun stop() {
        try {
            releaseButtons()
            proxy?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        } catch (_: Exception) {
        }
        keyExecutor.shutdownNow()
        proxy = null
        host = null
        registered = false
    }

    /** Devices already bonded to this phone, so the UI can offer a reconnect. */
    fun bondedHosts(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList().orEmpty()

    fun connectTo(device: BluetoothDevice) {
        proxy?.connect(device)
    }

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

    fun scroll(amount: Int) {
        if (host == null || amount == 0) return
        var left = amount
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

    fun releaseButtons() {
        if (buttons != 0) {
            buttons = 0
            send(0, 0, 0)
        }
    }

    // ---- keyboard -----------------------------------------------------------

    /** One 8-byte keyboard report: [modifiers, reserved, key, 0, 0, 0, 0, 0]. */
    private fun sendKey(mods: Int, usage: Int): Boolean {
        val p = proxy ?: return false
        val d = host ?: return false
        val body = ByteArray(8)
        body[0] = mods.toByte()
        body[2] = usage.toByte()
        return p.sendReport(d, KEYBOARD_REPORT_ID, body)
    }

    /** Press and release, with enough dwell that the host registers both. */
    private fun tap(mods: Int, usage: Int): Boolean {
        if (!sendKey(mods, usage)) return false
        Thread.sleep(KEY_HOLD)
        if (!sendKey(0, 0)) return false
        Thread.sleep(KEY_GAP)
        return true
    }

    private fun runScript(steps: List<HidKeys.Step>): Boolean {
        for (step in steps) {
            when (step) {
                is HidKeys.Step.Wait -> Thread.sleep(step.ms)
                is HidKeys.Step.Key -> if (!tap(step.mods, step.usage)) return false
                is HidKeys.Step.Text -> for (c in step.text) {
                    val s = HidKeys.stroke(c) ?: return false
                    if (!tap(s.mods, s.usage)) return false
                }
            }
        }
        return true
    }

    /**
     * Drive the host's own launcher to open [url].
     *
     * Runs on its own thread: the script is mostly sleeping, both between
     * keystrokes and while a launcher window appears, and none of that can
     * happen on the main thread or inside a HID callback.
     *
     * [onResult] is delivered on the main thread.
     */
    fun openUrl(
        url: String,
        os: HidKeys.HostOs,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        fun fail(why: String) {
            Log.w(TAG, "openUrl refused: $why")
            main.post { onResult?.invoke(false, why) }
        }

        if (host == null) return fail("not connected to a host")
        if (bootProtocol) {
            // boot protocol has no report IDs, so there is nowhere to address a
            // keyboard report — the bytes would be read as mouse movement
            return fail("host is in boot protocol — keyboard unavailable")
        }
        if (!HidKeys.isTypable(url)) return fail("that URL has characters this key map can't type")
        if (typing) return fail("already typing")

        typing = true
        keyExecutor.execute {
            // a held mouse button while a launcher opens is a recipe for
            // dragging something across the host's desktop
            releaseButtons()
            val ok = try {
                runScript(os.openUrlScript(url))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (e: Exception) {
                Log.e(TAG, "openUrl threw", e)
                false
            } finally {
                sendKey(0, 0)      // never leave a key stuck down on the host
                typing = false
            }
            Log.i(TAG, "openUrl finished ok=$ok os=${os.label}")
            main.post {
                onResult?.invoke(ok, if (ok) "opening on ${os.label}" else "the host stopped accepting keys")
            }
        }
    }
}
