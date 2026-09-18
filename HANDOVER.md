# Handover — Bluetooth HID connection rework

For picking this up in a new session **on the machine with the phone attached**.
Everything here was written in a cloud container with no Android SDK and no
route to a USB device, so nothing in it has run on real hardware yet. That is
the whole reason this file exists.

- **Branch:** `claude/gesture-mouse-apk-build-18aria`
- **PR:** https://github.com/abinrobby0312-lang/gesture-mouse/pull/2 (draft)
- **Repo:** `abinrobby0312-lang/gesture-mouse`
- **Version:** `versionCode 3`, `versionName 1.2.0`

## What changed and why

The reported symptom was "does not connect". The root defect: connecting was a
single `connect()` call whose return value was discarded. That call is a
*request*, not a result — it can return `false`, or return `true` and land back
in `DISCONNECTED` seconds later. Nothing retried, nothing reported, so a dead
connection was indistinguishable from a healthy `Ready`.

All connecting now goes through one attempt loop in `HidMouse.kt`
(`startAttempts` → `fireAttempt` → `onAttemptFailed`): it checks the return
value, arms an 8s timeout, retries up to 4 times with widening backoff
(1s/2s/4s), and ends with a stated reason.

Seven distinct bugs were fixed. In rough order of how likely each is to have
been *the* one:

1. **`proxyPending` could latch `true` forever.** `stop()` never cleared it, and
   `onServiceConnected` returned early for non-HID profiles before clearing it.
   Once latched, `start()` returned early for the rest of the run and only a
   force-stop recovered. This is the best fit for a fault that comes back
   intermittently.
2. **`ensureStarted()` dead-ended** when registered with no host. The only
   `connect()` in the class lived in the registration callback, which fires once
   per registration — so reopening the app never reconnected, despite the README
   promising it does. Now reconnects to the remembered host.
3. **Connecting on `BOND_BONDED` raced the stack.** That event fires when the
   pairing exchange finishes, not when service records are written. There is now
   a 1.2s settle (`connectAfterBond`).
4. **Exhausted attempts on a bonded host** is the cached-service-list case, which
   retrying cannot fix. Now detected, reported as `State.STALE_BOND`, and the UI
   offers to drop the pairing.
5. **`BONDING -> NONE` was ignored** — a refused pairing left the picker on
   "Pairing…", identical to one still in progress.
6. **No auto-reconnect on a dropped link.** Now reconnects; an explicit
   virtual-cable unplug deliberately does not, since that is the host declining.
7. **The QoS record.** Registration passed an explicit record with `MAX` latency;
   both QoS arguments are now `null`. **This is the one judgment call, not a
   clear-cut bug** — see "Open question" below.

Files touched: `HidMouse.kt` (most of it), `DevicePicker.kt` (bond handling),
`MainActivity.kt` (new states + re-pair dialog), `README.md`,
`.github/workflows/build.yml` (new), `app/build.gradle.kts` (version bump).

## Build and install

No Gradle wrapper exists in this repo, so use your own Gradle directly.

```bat
set JAVA_HOME=D:\android-tools\jdk
set ANDROID_HOME=D:\android-tools\sdk
set GRADLE_USER_HOME=D:\android-tools\gradle-home
cd D:\gesture-mouse-apk
git fetch origin claude/gesture-mouse-apk-build-18aria
git checkout claude/gesture-mouse-apk-build-18aria
D:\android-tools\gradle\bin\gradle.bat testReleaseUnitTest
D:\android-tools\gradle\bin\gradle.bat assembleRelease
```

APK lands in `app/build/outputs/apk/release/`.

```bat
adb install -r app\build\outputs\apk\release\app-release.apk
```

**If that fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the installed copy
was signed with a different key. `adb uninstall com.gesturemouse` first — this
also clears the remembered-host preference, which is fine and arguably what you
want for a clean test.

CI also builds this branch and uploads the APK as an artifact on the PR, if you
would rather not build locally. That build is debug-signed.

## Test plan

Run `adb logcat -s GMouse` throughout and keep the output. The tag covers the
entire startup and connection path.

### 1. Clean pair (the main case)

Start from no pairing at all — remove it on **both** the phone and the computer.
This matters: a host reads a device's service list once, when bonding, and
caches it forever, so a pairing made before the HID service was advertising has
no mouse in it.

1. Open the app, grant permissions.
2. Status strip should go amber: *Ready — pair "Gesture Mouse" from your computer*.
3. Tap **Pair**, pick the computer, confirm the code on both screens.
4. Expect in the log: `bonded, connecting HID to …` → `connectAfterBond … in 1200ms`
   → `connect attempt 1/4 … returned true` → `onConnectionStateChanged … state=2`.
5. Status turns teal. Move the cursor from the trackpad tab.

### 2. Reconnect on reopen (bug 2)

With a working pairing: background the app, reopen it. Expect
`ensureStarted: registered with no host — trying to reconnect` then
`autoReconnect(resume) -> …` and a reconnect with no tapping. **This never
worked before** — it is the clearest single confirmation that the rework landed.

### 3. Stale bond (bug 4)

Pair the phone to the computer through *system Bluetooth settings* rather than
the app, then open the app and try to connect. Expect four failed attempts, then
`giving up … stale service list?`, a red strip, and the re-pair dialog. Confirm
the dialog's "Unpair and retry" actually drops the pairing — `removeBond` is
reflection on a non-public API and **may fail on your phone**, in which case the
dialog should fall back to offering Bluetooth settings. Check which branch you get.

### 4. Drop and recover (bug 6)

While connected, turn Bluetooth off on the computer. Expect
`Disconnected — reconnecting…` and attempts. Turn it back on; it should recover
without touching the phone.

### 5. Regression check

Trackpad move/tap/two-finger scroll/right-click, and the Air tab gestures. The
report path (`send`, `move`, `scroll`, buttons) and the HID descriptor were
**not** touched, so these should behave exactly as before. If they don't,
something unintended happened.

## Reading the new log lines

| Line | Means |
|---|---|
| `connect attempt n/4 … returned true` | Request accepted; waiting on the host. Timeout is 8s. |
| `connect attempt n/4 … returned false` | Stack refused outright — usually not registered yet, or Bluetooth toggled mid-attempt. Retries immediately rather than waiting out the timeout. |
| `attempt n … failed: timed out` | Host never completed the link. |
| `attempt n … failed: host dropped the link` | Host accepted then dropped — the classic cached-SDP signature. |
| `giving up … stale service list?` | Out of attempts on a *bonded* host. Re-pair; retrying will not help. |
| `fireAttempt: not registered yet … deferring n/8` | Waiting on HID registration. Bounded at 8. |
| `The HID service didn't start` | Registration never completed. Reopen the app. |
| `autoReconnect(resume) -> …` | Reconnect on app reopen (bug 2 fixed). |
| `ensureStarted: no proxy — starting from scratch` | Cold start. |

## Open question — the QoS change

`registerApp` previously passed an explicit `BluetoothHidDeviceAppQosSettings`
with `MAX` latency; it now passes `null` for both QoS arguments, meaning "no
preference" so the stack takes the host's own L2CAP defaults. The reasoning: a
QoS record the host will not agree to is refused during channel setup, and that
surfaces as a connection that never completes rather than as a visible error.

The removed code's comment asserted the opposite — that macOS *abandons* L2CAP
setup without a QoS record. I believe that is backwards, but **I could not test
it**, and if your primary host is macOS this is the first thing to suspect if
connection behaviour got worse rather than better. Reverting it is a two-line
change in `registerApp`.

## If it still does not connect

Capture `adb logcat -s GMouse` for a full attempt and work from the table above.
The useful question is now answerable, which it wasn't before: **do you see
`connect attempt` lines at all?**

- **No `connect attempt` lines** → the problem is before connecting: registration
  or permissions. Look for `registerApp returned` and `onAppStatusChanged`.
- **`returned false` every time** → the stack is refusing the request. Check
  `registered=true` arrived first.
- **`returned true` then `host dropped the link`** → cached service list. Re-pair
  from scratch on both sides.
- **Reaches `state=2` but the pointer doesn't move** → not a connection problem.
  Check for `onSetProtocol boot=true`; a protocol mismatch parks the cursor.

Also pull the gesture log if Air-tab behaviour is involved:

```bat
adb pull /sdcard/Android/data/com.gesturemouse/files/gestures.jsonl
python analyze.py gestures.jsonl
```

Note `analyze.py` hardcodes `ADB = r"D:\android-tools\sdk\platform-tools\adb.exe"`.
On that machine it is fine; anywhere else, pass the file explicitly as above.
Making it find `adb` on `PATH` is an easy unrelated cleanup nobody has done yet.

## Rollback

The rework is one commit. To get back to the previous behaviour:

```bat
git revert <sha of "Rework Bluetooth HID connection logic">
```

`main` is untouched — it still has the old code, so simply checking out `main`
also gets you the previous build.

## Status when this was written

Confirmed green in CI (run 35326224837, commit `7cbe204`):

- `:app:compileReleaseKotlin` — **executed, no errors.** Two pre-existing
  `getParcelableExtra` deprecation warnings in `DevicePicker.kt`, unrelated to
  this change.
- `:app:testReleaseUnitTest` — **executed** (not `UP-TO-DATE`, not `NO-SOURCE`),
  `GestureEngineTest` passes.
- `:app:lintVitalRelease` — passed.
- `:app:packageRelease` — APK built and uploaded, 22.5 MB.

Artifact (debug-signed, expires per the repo's retention setting):
https://github.com/abinrobby0312-lang/gesture-mouse/actions/runs/35326224837/artifacts/10538209261

**Tested on real hardware: no.** Nothing here has connected to an actual host.
Compiling and passing unit tests says the code is well-formed; it says nothing
about whether the connection now works, because `GestureEngineTest` covers
gesture maths and none of it touches `HidMouse`. **That is what the next session
is for, and until it happens this is not deployable.**
