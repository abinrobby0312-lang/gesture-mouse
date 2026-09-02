# Gesture Mouse — Android app

Turns the phone into a Bluetooth mouse. Two ways to drive it: a normal touch
trackpad, or air gestures read from the camera.

**The computer needs nothing installed.** The phone registers itself as a
standard Bluetooth HID mouse, so the host pairs with it the same way it pairs
with any wireless mouse and uses the generic driver it already ships. Windows,
macOS, Linux, iPadOS, Android TV — all work, and none of them know this app
exists.

**Everything runs on the phone.** Hand tracking is on-device MediaPipe; no
video, no landmarks and no usage data leave the handset, and the app requests
no internet permission at all.

## Requirements

| | |
|---|---|
| Android | **9.0 (API 28)** or newer — when `BluetoothHidDevice` landed |
| CPU | ARM (`arm64-v8a` / `armeabi-v7a`); no x86 build |
| Permissions | Bluetooth (connect/scan/advertise) and Camera, both asked for on first run |
| Host | Anything that accepts a Bluetooth mouse — nothing to install on it |

## Install

Download `GestureMouse.apk`, copy it to the phone, and tap it. Android will ask
you to allow installing from this source — that prompt is expected for any app
that doesn't come from a store.

If you have `adb`:

```
adb install -r GestureMouse.apk
```

Upgrading over an older copy that was signed with a different key fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstall the old one first.

## Privacy

The camera feed is processed frame by frame on the device and never stored or
transmitted. The app writes one local file — `gestures.jsonl` in its own
external files directory — recording gesture timings used for tuning
thresholds. It contains no images and no personal data, is capped at 512 KB,
and goes away when the app is uninstalled. Nothing is ever uploaded.

## First run

A five-step walkthrough opens the first time you launch the app, covering
pairing, both sets of gestures, and the sleep behaviour. It's shown once and
then remembered — reopen it any time with the **?** button in the top right.

Permission prompts deliberately wait until the walkthrough is finished, so the
camera is only requested after you've been told what it's for.

## Connect

1. Open the app and grant Bluetooth + Camera when asked.
2. The status strip turns amber: *"Ready — pair Gesture Mouse from your computer"*.
3. Tap **Pair**. The app scans and lists nearby computers.
4. Tap yours. The app bonds and connects the mouse in one step — confirm the
   matching code on both screens.
5. Status turns teal. The cursor now answers to the phone.

After the first time it reconnects on its own whenever you open the app.

### Pair from the app, not from the computer

This matters more than it looks. A host reads a device's service list **once**,
while bonding, and caches it forever after. If the phone was already paired to
that computer for file transfer or audio, the cached list contains no mouse and
the host will never route pointer input to it — the connection reaches
CONNECTING and then times out.

If a computer was paired before this app existed, **remove the pairing on both
sides first**, then pair through the app's own Pair button with the status strip
showing *Ready*. That guarantees the HID service is advertising at the moment
the host takes its snapshot.

### The computer's name, not the app's

Bluetooth advertises the **adapter** name, so the phone appears under whatever
its Bluetooth name is (e.g. *"Abin's Pixel"*), not as "Gesture Mouse". The app
name only appears inside the HID service record.

## Host support

Nothing to install anywhere. Anything that speaks Bluetooth HID works.

| Host | Notes |
|---|---|
| **Windows 10/11** | Verified working. Stays in report protocol, so the scroll wheel is live. |
| **macOS** | System Settings → Bluetooth → find the phone → **Connect**. macOS usually switches the device into *boot protocol* during setup; see below. |
| **Linux** | Pair via your desktop's Bluetooth panel or `bluetoothctl`. |
| **iPadOS / iOS** | Settings → Bluetooth. Enable AssistiveTouch to get a pointer. |
| **Android / TV** | Pairs as a normal Bluetooth mouse. |

### Boot protocol vs. report protocol

A Bluetooth mouse can run in two modes and the *host* picks:

- **Report protocol** — the full descriptor: buttons, X, Y **and a wheel**, each
  report prefixed with a report ID. Windows uses this.
- **Boot protocol** — a fixed, minimal 3-byte report (buttons, X, Y) with no
  report ID and **no wheel field**. It exists so a mouse works in a BIOS before
  any driver loads. macOS commonly selects it.

The app tracks which mode the host asked for via `onSetProtocol` and changes its
packet layout to match. This is not optional: send report-protocol packets to a
host that asked for boot and the bytes land in the wrong fields — the pointer
sits still or jumps around.

**Consequence on macOS:** if the host selects boot protocol, scrolling is
unavailable — the protocol has nowhere to put it. Movement, left/right/middle
click and dragging all work normally.

## The two tabs

### Trackpad

An ordinary touch surface, following laptop conventions rather than inventing
new ones.

| Gesture | Action |
|---|---|
| One finger drag | Move cursor |
| Tap | Left click |
| Two-finger drag | Scroll |
| Two-finger tap | Right click |
| Tap, then press and hold | Drag — release to drop |
| Left / Mid / Right buttons | Held down while you hold them, so they drag too |

### Air

Camera watches your hand; nothing touches the screen.

| Gesture | Action |
|---|---|
| **Open palm** | On the pad, sweep gear — fast, for crossing the screen |
| **Pointing index** | On the pad, precise gear — slow, for landing on a target |
| Fist / sustained curl | Lifted — reposition without moving the cursor |
| **Dip index** down and back (<600ms) | Click |
| Pinch thumb + index, hold | Drag — release to drop |
| Pinch thumb + middle | Right click |
| Index + middle up, move hand up/down | Scroll |

Movement is **relative**, like a trackpad — your hand's position in frame never
maps to a screen coordinate. That's also why this works without the app ever
knowing the host's screen size.

#### It goes to sleep

The camera sees whatever passes in front of it, not just deliberate use, so
after **5 seconds** without a real action — move, click, scroll or drag — the
Air tab stops reacting to hands entirely and the readout says *sleeping*. That
way a phone propped on a desk can't have its cursor nudged by someone walking
past.

To wake it, hold **one open palm** steady for **3 seconds**. The readout counts
down while you hold. A brief tracking glitch won't reset the count, but taking
your hand away will.

#### Two gears

A far target wants speed; a small target wants precision. Hand shape picks which:

| Gear | Shape | Gain | Feel |
|---|---|---|---|
| Sweep | Open palm | `speed × 2.0` | Half a frame of hand movement crosses the screen |
| Precise | Pointing index | `speed × 0.55` | 2% of frame ≈ a 19px nudge |

A 3.6× ratio. Sweep toward the target, close to a point, ease onto it, dip to
click. Both gears are linear within themselves — no velocity guessing, so the
response is always predictable.

Changing gears mid-stroke never jumps the cursor: deltas are always measured
from the previous frame, so a new gain only affects what happens next.

#### Click vs. lift — same shape, told apart by time

Curling the index means two different things:

- Back up within 600ms → **click**
- Still curled after 600ms → **lifted off the pad**

The cursor freezes the moment the finger starts down, which is what you want for
both. 600ms is deliberately generous: a press doesn't have to be snappy, while a
real lift means holding the fist while you reposition, which takes over a second.

Click detection is suppressed while the thumb is near the index (pinching bends
the index too) and during an open-palm sweep (a tracking flicker mid-sweep would
otherwise stall the cursor and fire a click nobody asked for).

#### Why it tracks your knuckle

The tracking point is the **middle-finger knuckle**, not a fingertip. Knuckles
barely move when you flex a finger, so clicking, pinching and grabbing don't
drag the cursor with them.

## Layout

```
app/src/main/java/com/gesturemouse/
  HidMouse.kt          Bluetooth HID mouse — descriptor, reports, connection
  DevicePicker.kt      scan, bond and connect while HID is advertising
  GestureEngine.kt     hand landmarks -> mouse intents (pure logic, unit-tested)
  MainActivity.kt      tabs, permissions, pairing
  TrackpadFragment.kt  touch tab
  TrackpadSurface.kt   the touch surface itself
  AirFragment.kt       camera + MediaPipe, feeds GestureEngine
  HandOverlay.kt       skeleton and gear indicator
app/src/test/…         21 unit tests over GestureEngine
app/src/main/assets/hand_landmarker.task   MediaPipe model (7.5 MB, on-device)
```

Tabs are **tap-only** (`pager.isUserInputEnabled = false`). Horizontal swipe is
the trackpad's core gesture, so a swipeable pager would steal every sideways
drag.

## Troubleshooting

Everything the app does is logged under the tag `GMouse`:

```
adb logcat -s GMouse
```

A healthy startup looks like:

```
MainActivity.onCreate
startHid: BLUETOOTH_CONNECT granted=true
getProfileProxy(HID_DEVICE) returned true
onServiceConnected profile=19
registerApp returned true
onAppStatusChanged registered=true
onConnectionStateChanged device=… state=2      <- 2 means connected
```

| Symptom | Cause |
|---|---|
| No `GMouse` lines at all | The HID service never registered — reopen the app |
| Reaches `state=1` then `state=0` | Host has a cached service list with no mouse in it. Unpair on both sides and pair again from the app |
| Connected but the pointer won't move | Check for `onSetProtocol boot=true`; a protocol mismatch parks the cursor |
| No scrolling on macOS | Expected in boot protocol — it has no wheel field |

Hand tracking runs entirely on the phone. Nothing leaves the device.

## Tuning

The **speed** slider on the Air tab is the master sensitivity both gears
multiply. Everything else lives in `GestureEngine`'s companion object:

| Constant | Default | Raise it if… |
|---|---|---|
| `TAP_MAX` | 600ms | Deliberate clicks get read as lifting off |
| `CLICK_GAP` | 100ms | One dip registers as two clicks — but raising it much past here starts eating deliberate double-clicks |
| `PINCH_CLOSE` | 0.38 | Drags won't start — lower it if they start on their own |
| `PINCH_OPEN` | 0.52 | Drags drop early mid-move |
| `DRAG_HOLD` | 120ms | Brief contact causes accidental grabs |
| `SWEEP_GAIN` | 2.0 | A palm sweep can't cross the screen in one motion |
| `PRECISE_GAIN` | 0.55 | Pointing feels sluggish — lower for finer targeting |
| `DEAD_ZONE` | 0.15px | The cursor creeps while your hand is still |

Trackpad sensitivity is `TrackpadSurface.sensitivity` (1.2) — cursor pixels per
pixel of finger travel. Raise it if crossing the screen takes too many swipes.

## Building

Needs JDK 17, Android SDK 34 and Gradle 8.9. On the machine this was developed
on that toolchain lives in `D:\android-tools`:

```
set JAVA_HOME=D:\android-tools\jdk
set ANDROID_HOME=D:\android-tools\sdk
set GRADLE_USER_HOME=D:\android-tools\gradle-home
cd D:\gesture-mouse-apk
D:\android-tools\gradle\bin\gradle.bat testReleaseUnitTest
D:\android-tools\gradle\bin\gradle.bat assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. `local.properties` is
generated per machine and is not in the repo; point `sdk.dir` at your own SDK.

### Signing

A fresh clone has no keystore and **falls back to debug signing**, so it builds
and installs with no setup. That's fine for trying it out; it just means the
APK carries no authenticity guarantee, since the debug key ships with every
Android SDK.

To produce a properly signed release, create a keystore and a
`keystore.properties` beside it — both are gitignored:

```
keytool -genkeypair -v -keystore keystore/release.jks -alias gesturemouse \
        -keyalg RSA -keysize 2048 -validity 10000
```

```properties
# keystore.properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=gesturemouse
keyPassword=...
```

`app/build.gradle.kts` picks it up automatically when present. **Back the
keystore up somewhere offline** — lose it and you can no longer ship an update
that installs over an existing copy; users would have to uninstall first.

Bump `versionCode` in `app/build.gradle.kts` for every build you hand out.
Android refuses to install an APK whose `versionCode` is below what's already
on the device.

### Gotchas

`noCompress += "task"` in the Gradle config matters — MediaPipe memory-maps the
model, and it fails at runtime if aapt deflates it.

R8/minification is deliberately off: nearly all of the ~45 MB is MediaPipe's
native libraries and the hand model, which shrinking doesn't touch, and
MediaPipe resolves enough by reflection that enabling it risks a runtime break
that would only surface on someone else's phone.

## License

MIT — see [LICENSE](LICENSE).
