# PicoLink

An Android app + MicroPython firmware pair that turns a **Raspberry Pi Pico W**
into a Bluetooth-controlled I/O board. The app connects over **Bluetooth Low
Energy** (Nordic UART Service) and gives you full control of the Pico's clock,
GPIO pins, PWM, ADC, schedules and on-device programs.

> **Why the Pico W?** The original Pico has no radio at all. The Pico W's
> CYW43439 chip provides BLE, which is what MicroPython exposes — so this
> project targets the **Pico W** (or Pico 2 W).

```
PicoProject/
├── app/        Android app (Kotlin, Jetpack Compose, Material 3)
├── pico/       MicroPython firmware for the Pico W (main.py)
└── gradle/     Build configuration + wrapper
```

---

## Features

### Connection
- BLE device scanner with live RSSI, signal-strength sorting and name/address filtering
- One-tap reconnect to the last used device
- Optional **auto-connect on launch**
- **Auto-reconnect with exponential backoff** when the link drops unexpectedly
- MTU negotiation (247 bytes) with automatic message chunking/reassembly
- Clean runtime-permission flow for Android 12+ (`BLUETOOTH_SCAN`/`CONNECT`,
  declared `neverForLocation`) and legacy devices (location permission)

### Dashboard
- Live connection status and signal strength
- Firmware version, uptime, free memory, CPU clock
- On-chip **temperature sensor** readout
- Onboard **LED toggle**
- Pico clock display with **drift vs phone** and last-sync indicator

### Time
- One-tap **time sync** (sends phone epoch + timezone offset to the Pico)
- Optional **automatic time sync on every connect**
- Read back the Pico's clock at any time

### Pins
- All user GPIOs (GP0–GP22, GP26–GP28) in a filterable list
- Per-pin **mode configuration**: output, input, input pull-up, input pull-down
- Digital **write** (toggle switch with haptic feedback) and **read**
- **PWM** per pin: frequency field + duty-cycle slider, live apply, stop
- **ADC reads** (GP26/27/28) in volts
- **Watch mode**: subscribe to an input pin and get push events when it changes
- Color-coded pin state indicators

### Schedules (run on the Pico, phone not required)
- Create/edit/delete schedules with a Material time picker
- **Days-of-week repeat** (weekdays/weekends/custom), or **one-shot** (fires
  once, then disables itself)
- Actions: set a pin high/low, start a PWM output, or run a named program
- Enable/disable without deleting
- Persisted to the Pico's flash — they survive reboots and keep firing while
  the app is disconnected (clock must be synced after power loss)

### Programs
- **Built-in programs** shipped with the firmware (`blink`, `sos`, `heartbeat`)
- **Macro builder**: compose custom step sequences (set pin → wait → PWM → …)
  in the app and save them to the Pico's flash
- Run / stop programs from the app or trigger them from schedules
- Completion events pushed back to the phone

### Terminal & log
- Full session log: TX/RX/events/errors, color-coded, monospaced, timestamped
- Raw JSON command console **plus shorthand commands**
  (`ping`, `info`, `temp`, `time`, `sync`, `led on`, `read 15`,
  `write 15 1`, `adc 26`, `run blink`, `reset`)
- Command history recall
- **Share/export the log** via any share target

### Settings & polish
- Light/dark/system theme + **Material You dynamic color**
- Keep-screen-on option
- Haptic feedback toggle
- Configurable status poll interval (2–60 s)
- All preferences persisted with DataStore
- Edge-to-edge Material 3 UI throughout

---

## Getting started

### 1. Flash the Pico W

1. Install [MicroPython for Pico W](https://micropython.org/download/RPI_PICO_W/)
   (hold BOOTSEL, plug in USB, copy the `.uf2`).
2. Copy `pico/main.py` to the board (Thonny: *File → Save as → Raspberry Pi
   Pico*, name it `main.py`; or `mpremote cp pico/main.py :main.py`).
3. Reboot the Pico. It starts advertising as **PicoLink**.

See [pico/README.md](pico/README.md) for details and the full protocol spec.

### 2. Build the Android app

Open the project in **Android Studio** (Ladybug or newer) and run the `app`
configuration, or from the command line:

```bash
./gradlew :app:assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Requirements: JDK 17+, Android SDK 35. Min supported device: Android 8.0
(API 26); BLE hardware required.

### 3. Connect

1. Open PicoLink, grant the Bluetooth permission.
2. Tap the Bluetooth icon → **Scan for devices** → connect to *PicoLink*.
3. The app syncs the Pico's clock automatically and loads pins, schedules
   and programs.

---

## Protocol overview

Newline-delimited JSON over the Nordic UART Service
(`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`).

| Direction | Example |
|---|---|
| Request | `{"cmd":"pin.write","id":7,"pin":15,"value":1}` |
| Reply | `{"ok":true,"id":7,"pin":15,"value":1}` |
| Error | `{"ok":false,"id":7,"err":"invalid pin 99"}` |
| Event (push) | `{"evt":"pin","pin":15,"value":1}` |

Command groups: `ping`, `sys.info`, `time.set/get`, `pin.mode/read/write/read_all`,
`pwm.set/stop`, `adc.read`, `temp.read`, `led`, `watch.add/del`,
`sched.list/add/del/enable`, `prog.list/run/stop/save/del`, `reset`.
The complete field-level reference is in [pico/README.md](pico/README.md).

## Architecture notes

- **App**: single-activity Jetpack Compose, `MainViewModel` as the hub,
  `BleManager` for GATT (serialized write queue, MTU-aware chunking,
  newline reframing), `Protocol` for typed command building/parsing,
  DataStore for preferences. No third-party runtime dependencies beyond
  AndroidX.
- **Firmware**: `uasyncio` + `aioble`; independent tasks for the BLE link,
  the 1 Hz scheduler and the 20 Hz input watcher, so schedules fire even
  with no phone connected. Schedules and macros are JSON files on flash.
- **Source of truth**: schedules and programs live on the Pico; the app is a
  remote control, which means multiple phones stay consistent.
