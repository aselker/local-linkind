# AiDot Bulb — Android app + widget

Account-free local control of Linkind / AiDot (Leedarson) bulbs (e.g. **LS01017116 RGBTW**)
over Bluetooth LE, from an Android phone. No AiDot cloud account. This is a native Kotlin port of
[`../aidot_control.py`](../aidot_control.py) — same verified BLE protocol, no bundled Python.

Two things:

- **Provision** a bulb account-free: scan over BLE, auto-resolve the product `modelId` (needed for
  the sign-in key) by trying the bundled list, and save it.
- **Control** from a **home-screen widget**, organised into **groups**: on/off, brightness, and
  colour-temperature (plus optional RGB). The widget shows **one control block per group**, and a
  tap fans out to every bulb in that group (in parallel). A group can be a **single bulb** or
  several — "Desk", "Living room", "Everything upstairs", whatever you like. The brightness/CCT/RGB
  **preset values are configured per group**; the widget shows a button for each. RGB presets are
  empty by default (white-focused use) — add hex colours in the group's edit screen if you want
  them; they render as colour swatches.
- **Bulbs vs. groups:** a *bulb* is a provisioned device (its identity — address, model, name); a
  *group* is what the widget drives. When you first update, every existing bulb is migrated to its
  own size-1 group (keeping its name and presets), and a freshly provisioned bulb also gets a
  size-1 group automatically — so a new bulb shows up on the widget right away. Merge bulbs into
  bigger groups, or split them out, from **Add group** / a group's **Edit** screen in the app.
- **Instant tap feedback.** Talking to a bulb over BLE takes a second or two; so a press doesn't feel
  like nothing happened, the tapped button lights up (in blue) and the group's dot turns to a ⟳
  "working…" mark the moment you tap — *before* the radio op runs — then settles to the real on/off
  state when it finishes (whether it succeeded or the bulb was unreachable).
- Each group shows **live on/off state**: the On or Off button is highlighted to match the group's
  current state and a ● / ○ dot sits by the name. This is optimistic — it reflects the last state
  the widget/app set (updated on every tap and whenever you open the app), not a background poll, so
  it stays zero-cost at idle. If you toggle a bulb elsewhere it can drift until the next tap.
- **All on / All off** (shown when you have 2+ groups) drives every bulb at once; bulbs run in
  parallel.
- **Tap several buttons in quick succession** without errors: ops are serialized per bulb (see
  below), so rapid taps queue and run in order instead of two connections colliding.
- **A bulb that's switched off at the wall doesn't drag down the others.** It's simply unreachable,
  so its op gives up quickly (~5 s) and is then skipped for a few seconds, instead of re-stalling
  the Bluetooth stack on every tap — the bulbs that are on stay responsive. A group tap updates
  only the members that actually responded, so an off bulb isn't shown as "on"; switch it back on
  and the next tap controls it again.
- Compact layout with roomy, tappable buttons; the widget defaults to a 3×4 cell size and is
  resizable. Fewer groups than bulbs means fewer control blocks, so the buttons can be larger.

## Low-resource by design

- The widget declares `updatePeriodMillis="0"` — it **never wakes on its own**. Nothing runs in the
  background at idle: no service, no polling, no wakelock, no alarms.
- The *only* work happens when you tap a button: a short-lived broadcast
  (`WidgetActionReceiver`, via `goAsync()`) runs the op, then finishes. After it returns there is no
  service left running (verified with `dumpsys activity services`). Android may keep an empty,
  reclaimable cached process around; it does no work.
- **`BleConductor`** serializes ops **per bulb** with a `Mutex` (so quick successive taps queue
  instead of colliding) and keeps a connection warm for ~4 s after each op, so a burst reuses it
  (fast) before it closes back to zero footprint. Different bulbs use different locks, so All on/off
  controls them in parallel. It also remembers a bulb that just failed to connect and short-circuits
  taps to it for a few seconds, so one switched-off bulb doesn't keep tying up the radio.

## Build

Requires the Android SDK (platform 36) and a JDK. The Gradle daemon is pinned to JDK 21 in
`gradle.properties` (AGP 9 doesn't run on JDK 25 yet); change `org.gradle.java.home` for your setup,
and set the SDK path in `local.properties` (`sdk.dir=/path/to/Android/Sdk`).

```sh
cd android
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install to a connected device/emulator
```

Toolchain: AGP 9.3.1 (built-in Kotlin), Gradle 9.6.1, minSdk 26, compile/target 36. Dependencies are
just `androidx.appcompat`, `androidx.core`, and `kotlinx-coroutines-android`.

## Use

1. Launch **AiDot Bulb**; grant the Bluetooth permissions (scan + connect; location on Android ≤ 11).
2. **Add bulb** → scan → pick your bulb. First time, it resolves the model-id (the LS01017116 is
   seeded so it signs in on the first try; other SKUs try the bundled `model_ids.txt`).
3. **Edit** a bulb to rename it (or delete it). A newly added bulb already has its own size-1
   group, so it is controllable immediately.
4. **Add group** (or **Edit** an existing one) to name a group, tick which bulbs belong to it, and
   set the brightness / CCT (and optional RGB) presets the widget shows for it.
5. **Add widget to home screen** (button in the app, or the launcher's widget picker). Tap its
   buttons to control each group.

## Can I test control on the emulator?

No — the emulator's virtual Bluetooth can't reach real bulbs. The emulator verifies the UI,
provisioning scan, the store, widget rendering, and the BLE plumbing (a tap connects and times out
cleanly). **Actual on/off/brightness/CCT must be run on a physical phone** near the bulbs.

## Layout

```
app/src/main/
  AndroidManifest.xml
  assets/model_ids.txt                 # candidate modelIds for resolve (copy of ../model_ids.txt)
  java/com/aidotble/widget/
    ble/      Crypto, Protocol, BulbClient, Discovery, BleOps   # the protocol port
    data/     Bulb, BulbStore, Group, GroupStore                # JSON-in-SharedPreferences
    ui/       MainActivity, ProvisionActivity, BulbEditActivity, GroupEditActivity, Ui, Permissions
    widget/   BulbWidgetProvider, WidgetActionReceiver          # the home-screen widget
  res/layout/ widget_root|row|button|label.xml
  res/xml/    bulb_widget_info.xml                              # updatePeriodMillis=0
```

The BLE protocol (keys, framing, sign-in, setAttrs) is documented in `ble/Crypto.kt` /
`ble/Protocol.kt` and in [`../FINDINGS.md`](../FINDINGS.md).
