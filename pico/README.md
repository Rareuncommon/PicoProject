# PicoLink firmware (Raspberry Pi Pico W)

MicroPython firmware that pairs with the PicoLink Android app over BLE.

## Install

1. Flash [MicroPython for the Pico W](https://micropython.org/download/RPI_PICO_W/)
   — hold **BOOTSEL** while plugging in USB, then copy the `.uf2` onto the
   `RPI-RP2` drive. Use a recent build (aioble is bundled with official
   Pico W builds).
2. Copy `main.py` to the board's filesystem:
   - **Thonny**: open `main.py`, *File → Save as… → Raspberry Pi Pico*, keep
     the name `main.py`, or
   - **mpremote**: `mpremote cp main.py :main.py`
3. Power-cycle the board. It advertises as **PicoLink**.

Files the firmware creates on flash:

| File | Purpose |
|---|---|
| `schedules.json` | persisted schedules |
| `macros.json` | persisted user programs |

## Protocol reference

Transport: Nordic UART Service, newline-delimited JSON.
Every request carries an `id`; the reply echoes it.

### System
| Command | Fields | Reply |
|---|---|---|
| `ping` | — | `{"pong":true}` |
| `sys.info` | — | `fw, uptime, mem_free, cpu_freq, epoch, temp, led, prog` |
| `reset` | — | resets the board 500 ms later |

### Time
| Command | Fields | Notes |
|---|---|---|
| `time.set` | `epoch` (UTC seconds), `tz` (minutes) | sets the RTC to local time |
| `time.get` | — | returns `epoch` (UTC), `synced`, `tz` |

### Pins
| Command | Fields | Notes |
|---|---|---|
| `pin.mode` | `pin`, `mode` (`out`/`in`/`in_pu`/`in_pd`) | |
| `pin.write` | `pin`, `value` (0/1) | auto-configures as output |
| `pin.read` | `pin` | auto-configures as input if unset |
| `pin.read_all` | — | snapshot of all configured pins |
| `pwm.set` | `pin`, `freq` (Hz), `duty` (0–100) | |
| `pwm.stop` | `pin` | releases the pin |
| `adc.read` | `pin` (26/27/28) | returns `raw` and `volts` |
| `temp.read` | — | on-chip sensor, °C |
| `led` | `value` (0/1) | onboard LED |
| `watch.add` / `watch.del` | `pin` | push `{"evt":"pin",...}` on change |

Valid pins: GP0–GP22, GP26–GP28 (GP23–25/29 are internal on the Pico W).

### Schedules
| Command | Fields |
|---|---|
| `sched.list` | — |
| `sched.add` | `sched`: `{id, name, hh, mm, days:[0=Mon…6=Sun], enabled, action}` |
| `sched.del` | `sid` |
| `sched.enable` | `sid`, `enabled` |

`action` is one of
`{"type":"pin","pin":15,"value":1}`,
`{"type":"pwm","pin":15,"freq":1000,"duty":50}`,
`{"type":"prog","prog":"blink"}`.
An **empty `days` list makes the schedule one-shot**: it fires at the next
matching time, then disables itself.

Schedules only fire after the clock has been synced (`time.set`) since the
last power-up — the Pico W has no battery-backed RTC.

### Programs
| Command | Fields |
|---|---|
| `prog.list` | — |
| `prog.run` | `name` (built-in or saved macro) |
| `prog.stop` | — |
| `prog.save` | `name`, `steps` (≤32) |
| `prog.del` | `name` |

Macro steps: `{"op":"pin","pin":15,"value":1}`,
`{"op":"pwm","pin":15,"freq":1000,"duty":50}`, `{"op":"sleep","ms":500}`.

Built-ins: `blink`, `sos`, `heartbeat` (onboard LED demos).

### Events (Pico → phone)
| Event | Payload |
|---|---|
| `hello` | sent on connect: `fw`, `name` |
| `pin` | watched pin changed: `pin`, `value`, `t` |
| `sched_fired` | `name`, `sid` |
| `prog_done` | `name` |
| `log` | diagnostic `msg` |
