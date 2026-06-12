"""PicoLink firmware for the Raspberry Pi Pico W.

Exposes a Nordic UART Service (NUS) over BLE and speaks a JSON-lines
protocol with the PicoLink Android app:

    request : {"cmd":"pin.write","id":7,"pin":15,"value":1}
    reply   : {"ok":true,"id":7,...}  or  {"ok":false,"id":7,"err":"..."}
    event   : {"evt":"pin","pin":15,"value":1}   (unsolicited push)

Features:
  - digital pin read/write with pull-up/pull-down configuration
  - PWM output (frequency + duty cycle)
  - ADC reads (GP26-28) and the on-chip temperature sensor
  - onboard LED control
  - clock sync from the phone (epoch + timezone offset)
  - schedules (time-of-day + days-of-week) persisted to flash, executed
    on-device so they keep firing while the phone is away
  - named programs: built-ins plus user-defined step macros persisted
    to flash
  - input pin watching with push notifications on change

Requires MicroPython for Pico W (aioble is bundled with official builds).
Copy this file to the Pico W as main.py.
"""

import json
import time

import aioble
import bluetooth
import gc
import machine
import uasyncio as asyncio
from machine import ADC, PWM, Pin, RTC

FIRMWARE_VERSION = "PicoLink 1.0.0"
DEVICE_NAME = "PicoLink"

_UART_SERVICE = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_RX = bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")  # phone -> pico
_UART_TX = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")  # pico -> phone

_ADV_INTERVAL_US = 250_000

SCHEDULES_FILE = "schedules.json"
MACROS_FILE = "macros.json"

# GP23-25 and GP29 are wired to internal functions on the Pico W.
VALID_PINS = set(range(0, 23)) | {26, 27, 28}
ADC_PINS = {26: 0, 27: 1, 28: 2}

# MicroPython's epoch is 2000-01-01 on most embedded ports; phones use 1970.
EPOCH_OFFSET = 946684800 if time.gmtime(0)[0] == 2000 else 0


# --------------------------------------------------------------------------
# Global state
# --------------------------------------------------------------------------

led = Pin("LED", Pin.OUT, value=0)
rtc = RTC()
temp_adc = ADC(4)

# pin -> {"mode": str, "obj": Pin|PWM, "freq": int, "duty": float, "watched": bool}
pins = {}
schedules = []          # list of schedule dicts (see protocol docs)
macros = {}             # name -> list of step dicts
tz_offset_min = 0
time_synced = False
uptime_s = 0
program_task = None
program_name = None

connection = None       # active BLE connection (or None)
tx_characteristic = None
send_lock = asyncio.Lock()


# --------------------------------------------------------------------------
# Persistence
# --------------------------------------------------------------------------

def load_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, data):
    try:
        with open(path, "w") as f:
            json.dump(data, f)
    except OSError as e:
        print("flash write failed:", e)


def load_state():
    global schedules, macros
    schedules = load_json(SCHEDULES_FILE, [])
    macros = load_json(MACROS_FILE, {})


# --------------------------------------------------------------------------
# Transport
# --------------------------------------------------------------------------

async def send_obj(obj):
    """Serialize and notify, chunked to the connection MTU."""
    global connection
    conn = connection
    if conn is None or tx_characteristic is None:
        return
    data = (json.dumps(obj) + "\n").encode()
    try:
        mtu = conn.mtu or 23
    except Exception:
        mtu = 23
    chunk = max(20, mtu - 3)
    async with send_lock:
        try:
            for i in range(0, len(data), chunk):
                tx_characteristic.notify(conn, data[i:i + chunk])
                await asyncio.sleep_ms(5)
        except Exception:
            pass  # connection dropped mid-send; the main loop handles it


def reply_ok(req_id, **extra):
    out = {"ok": True, "id": req_id}
    out.update(extra)
    return out


def reply_err(req_id, message):
    return {"ok": False, "id": req_id, "err": message}


# --------------------------------------------------------------------------
# Pins
# --------------------------------------------------------------------------

def release_pin(pin):
    entry = pins.pop(pin, None)
    if entry and entry["mode"] == "pwm":
        entry["obj"].deinit()


def configure_pin(pin, mode):
    if pin not in VALID_PINS:
        raise ValueError("invalid pin %d" % pin)
    release_pin(pin)
    if mode == "out":
        obj = Pin(pin, Pin.OUT, value=0)
    elif mode == "in":
        obj = Pin(pin, Pin.IN)
    elif mode == "in_pu":
        obj = Pin(pin, Pin.IN, Pin.PULL_UP)
    elif mode == "in_pd":
        obj = Pin(pin, Pin.IN, Pin.PULL_DOWN)
    else:
        raise ValueError("invalid mode %s" % mode)
    pins[pin] = {"mode": mode, "obj": obj, "freq": 1000, "duty": 0.0,
                 "watched": False, "last": obj.value()}
    return pins[pin]


def configure_pwm(pin, freq, duty_percent):
    if pin not in VALID_PINS:
        raise ValueError("invalid pin %d" % pin)
    freq = min(max(int(freq), 8), 1_000_000)
    duty_percent = min(max(float(duty_percent), 0.0), 100.0)
    entry = pins.get(pin)
    if entry is None or entry["mode"] != "pwm":
        release_pin(pin)
        pwm = PWM(Pin(pin))
        entry = {"mode": "pwm", "obj": pwm, "freq": freq,
                 "duty": duty_percent, "watched": False, "last": 0}
        pins[pin] = entry
    pwm = entry["obj"]
    pwm.freq(freq)
    pwm.duty_u16(int(duty_percent * 65535 / 100))
    entry["freq"] = freq
    entry["duty"] = duty_percent
    return entry


def pin_snapshot():
    out = []
    for pin, entry in pins.items():
        item = {"pin": pin, "mode": entry["mode"], "watched": entry["watched"]}
        if entry["mode"] == "pwm":
            item["freq"] = entry["freq"]
            item["duty"] = entry["duty"]
        else:
            item["value"] = entry["obj"].value()
        out.append(item)
    return out


def read_temperature():
    raw = temp_adc.read_u16()
    volts = raw * 3.3 / 65535
    return 27 - (volts - 0.706) / 0.001721


def utc_epoch_1970():
    if not time_synced:
        return 0
    return time.time() + EPOCH_OFFSET - tz_offset_min * 60


# --------------------------------------------------------------------------
# Programs
# --------------------------------------------------------------------------

async def builtin_blink(times=10, period_ms=300):
    for _ in range(times):
        led.value(1)
        await asyncio.sleep_ms(period_ms)
        led.value(0)
        await asyncio.sleep_ms(period_ms)


async def builtin_sos():
    pattern = [1, 1, 1, 3, 3, 3, 1, 1, 1]  # units: dot=1, dash=3
    for units in pattern:
        led.value(1)
        await asyncio.sleep_ms(200 * units)
        led.value(0)
        await asyncio.sleep_ms(200)


async def builtin_heartbeat():
    for _ in range(20):
        led.value(1)
        await asyncio.sleep_ms(80)
        led.value(0)
        await asyncio.sleep_ms(120)
        led.value(1)
        await asyncio.sleep_ms(80)
        led.value(0)
        await asyncio.sleep_ms(700)


BUILTIN_PROGRAMS = {
    "blink": builtin_blink,
    "sos": builtin_sos,
    "heartbeat": builtin_heartbeat,
}


async def run_macro(steps):
    for step in steps:
        op = step.get("op")
        if op == "pin":
            entry = pins.get(step["pin"])
            if entry is None or entry["mode"] != "out":
                entry = configure_pin(step["pin"], "out")
            entry["obj"].value(1 if step.get("value") else 0)
        elif op == "pwm":
            configure_pwm(step["pin"], step.get("freq", 1000),
                          step.get("duty", 50))
        elif op == "sleep":
            await asyncio.sleep_ms(int(step.get("ms", 100)))


async def program_wrapper(name, coro):
    global program_task, program_name
    try:
        await coro
        await send_obj({"evt": "prog_done", "name": name})
    except asyncio.CancelledError:
        pass
    finally:
        program_task = None
        program_name = None


def start_program(name):
    global program_task, program_name
    if program_task is not None:
        program_task.cancel()
    if name in BUILTIN_PROGRAMS:
        coro = BUILTIN_PROGRAMS[name]()
    elif name in macros:
        coro = run_macro(macros[name])
    else:
        raise ValueError("unknown program %s" % name)
    program_name = name
    program_task = asyncio.create_task(program_wrapper(name, coro))


# --------------------------------------------------------------------------
# Schedules
# --------------------------------------------------------------------------

def execute_action(action):
    kind = action.get("type")
    if kind == "pin":
        entry = pins.get(action["pin"])
        if entry is None or entry["mode"] != "out":
            entry = configure_pin(action["pin"], "out")
        entry["obj"].value(1 if action.get("value") else 0)
    elif kind == "pwm":
        configure_pwm(action["pin"], action.get("freq", 1000),
                      action.get("duty", 50))
    elif kind == "prog":
        start_program(action.get("prog", ""))


async def scheduler_loop():
    global uptime_s
    last_fired_minute = {}  # schedule id -> "yday:hh:mm" we already fired on
    while True:
        await asyncio.sleep(1)
        uptime_s += 1
        if not time_synced:
            continue
        now = time.localtime()
        hh, mm, weekday, yday = now[3], now[4], now[6], now[7]
        stamp = "%d:%d:%d" % (yday, hh, mm)
        for sched in schedules:
            if not sched.get("enabled", True):
                continue
            if sched.get("hh") != hh or sched.get("mm") != mm:
                continue
            days = sched.get("days", [])
            if days and weekday not in days:
                continue
            sid = sched.get("id")
            if last_fired_minute.get(sid) == stamp:
                continue
            last_fired_minute[sid] = stamp
            try:
                execute_action(sched.get("action", {}))
                if not days:
                    # Empty day set = one-shot: disable after firing once.
                    sched["enabled"] = False
                    save_json(SCHEDULES_FILE, schedules)
                await send_obj({"evt": "sched_fired",
                                "name": sched.get("name", "schedule"),
                                "sid": sid})
            except Exception as e:
                await send_obj({"evt": "log", "msg": "schedule error: %s" % e})


async def watcher_loop():
    """Polls watched input pins and pushes change events."""
    while True:
        await asyncio.sleep_ms(50)
        for pin, entry in pins.items():
            if not entry["watched"] or entry["mode"] == "pwm":
                continue
            value = entry["obj"].value()
            if value != entry["last"]:
                entry["last"] = value
                await send_obj({"evt": "pin", "pin": pin, "value": value,
                                "t": utc_epoch_1970()})


# --------------------------------------------------------------------------
# Command dispatch
# --------------------------------------------------------------------------

def sys_info(req_id):
    gc.collect()
    return reply_ok(
        req_id,
        fw=FIRMWARE_VERSION,
        uptime=uptime_s,
        mem_free=gc.mem_free(),
        cpu_freq=machine.freq(),
        epoch=utc_epoch_1970(),
        temp=round(read_temperature(), 2),
        led=led.value(),
        prog=program_name,
    )


async def handle_command(msg):
    global tz_offset_min, time_synced
    cmd = msg.get("cmd")
    req_id = msg.get("id", -1)

    try:
        if cmd == "ping":
            return reply_ok(req_id, pong=True)

        if cmd == "sys.info":
            return sys_info(req_id)

        if cmd == "time.set":
            epoch = int(msg["epoch"])
            tz_offset_min = int(msg.get("tz", 0))
            lt = time.gmtime(epoch - EPOCH_OFFSET + tz_offset_min * 60)
            rtc.datetime((lt[0], lt[1], lt[2], lt[6], lt[3], lt[4], lt[5], 0))
            time_synced = True
            return reply_ok(req_id, epoch=utc_epoch_1970())

        if cmd == "time.get":
            return reply_ok(req_id, epoch=utc_epoch_1970(),
                            synced=time_synced, tz=tz_offset_min)

        if cmd == "pin.mode":
            entry = configure_pin(int(msg["pin"]), msg["mode"])
            return reply_ok(req_id, value=entry["obj"].value())

        if cmd == "pin.write":
            pin = int(msg["pin"])
            entry = pins.get(pin)
            if entry is None or entry["mode"] != "out":
                entry = configure_pin(pin, "out")
            entry["obj"].value(1 if msg.get("value") else 0)
            return reply_ok(req_id, pin=pin, value=entry["obj"].value())

        if cmd == "pin.read":
            pin = int(msg["pin"])
            entry = pins.get(pin)
            if entry is None:
                entry = configure_pin(pin, "in")
            if entry["mode"] == "pwm":
                return reply_err(req_id, "pin %d is in PWM mode" % pin)
            return reply_ok(req_id, pin=pin, value=entry["obj"].value())

        if cmd == "pin.read_all":
            return reply_ok(req_id, pins=pin_snapshot())

        if cmd == "pwm.set":
            entry = configure_pwm(int(msg["pin"]), msg.get("freq", 1000),
                                  msg.get("duty", 0))
            return reply_ok(req_id, freq=entry["freq"], duty=entry["duty"])

        if cmd == "pwm.stop":
            release_pin(int(msg["pin"]))
            return reply_ok(req_id)

        if cmd == "adc.read":
            pin = int(msg["pin"])
            if pin not in ADC_PINS:
                return reply_err(req_id, "pin %d is not an ADC pin" % pin)
            raw = ADC(ADC_PINS[pin]).read_u16()
            return reply_ok(req_id, pin=pin, raw=raw,
                            volts=round(raw * 3.3 / 65535, 4))

        if cmd == "temp.read":
            return reply_ok(req_id, temp=round(read_temperature(), 2))

        if cmd == "led":
            led.value(1 if msg.get("value") else 0)
            return reply_ok(req_id, value=led.value())

        if cmd == "watch.add":
            pin = int(msg["pin"])
            entry = pins.get(pin)
            if entry is None:
                entry = configure_pin(pin, "in")
            if entry["mode"] == "pwm":
                return reply_err(req_id, "cannot watch a PWM pin")
            entry["watched"] = True
            entry["last"] = entry["obj"].value()
            return reply_ok(req_id, pin=pin)

        if cmd == "watch.del":
            entry = pins.get(int(msg["pin"]))
            if entry:
                entry["watched"] = False
            return reply_ok(req_id)

        if cmd == "sched.list":
            return reply_ok(req_id, schedules=schedules)

        if cmd == "sched.add":
            sched = msg["sched"]
            sid = int(sched["id"])
            schedules[:] = [s for s in schedules if s.get("id") != sid]
            schedules.append(sched)
            save_json(SCHEDULES_FILE, schedules)
            return reply_ok(req_id, sid=sid)

        if cmd == "sched.del":
            sid = int(msg["sid"])
            schedules[:] = [s for s in schedules if s.get("id") != sid]
            save_json(SCHEDULES_FILE, schedules)
            return reply_ok(req_id)

        if cmd == "sched.enable":
            sid = int(msg["sid"])
            for s in schedules:
                if s.get("id") == sid:
                    s["enabled"] = bool(msg.get("enabled", True))
            save_json(SCHEDULES_FILE, schedules)
            return reply_ok(req_id)

        if cmd == "prog.list":
            out = [{"name": n, "builtin": True, "steps": []}
                   for n in BUILTIN_PROGRAMS]
            out += [{"name": n, "builtin": False, "steps": s}
                    for n, s in macros.items()]
            return reply_ok(req_id, programs=out)

        if cmd == "prog.run":
            start_program(msg["name"])
            return reply_ok(req_id, name=msg["name"])

        if cmd == "prog.stop":
            if program_task is not None:
                program_task.cancel()
            return reply_ok(req_id)

        if cmd == "prog.save":
            name = str(msg["name"])[:20]
            if name in BUILTIN_PROGRAMS:
                return reply_err(req_id, "name is reserved by a built-in")
            steps = msg["steps"]
            if not isinstance(steps, list) or len(steps) > 32:
                return reply_err(req_id, "steps must be a list of up to 32 items")
            macros[name] = steps
            save_json(MACROS_FILE, macros)
            return reply_ok(req_id, name=name)

        if cmd == "prog.del":
            macros.pop(str(msg.get("name")), None)
            save_json(MACROS_FILE, macros)
            return reply_ok(req_id)

        if cmd == "reset":
            asyncio.create_task(deferred_reset())
            return reply_ok(req_id, resetting=True)

        return reply_err(req_id, "unknown command: %s" % cmd)
    except (KeyError, ValueError, TypeError) as e:
        return reply_err(req_id, str(e))


async def deferred_reset():
    await asyncio.sleep_ms(500)
    machine.reset()


# --------------------------------------------------------------------------
# BLE main loop
# --------------------------------------------------------------------------

async def rx_loop(rx):
    buffer = b""
    while True:
        _, data = await rx.written()
        buffer += data
        while b"\n" in buffer:
            line, buffer = buffer.split(b"\n", 1)
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                await send_obj(reply_err(-1, "invalid JSON"))
                continue
            try:
                response = await handle_command(msg)
            except Exception as e:
                response = reply_err(msg.get("id", -1), "internal: %s" % e)
            if response is not None:
                await send_obj(response)
        if len(buffer) > 4096:
            buffer = b""  # discard runaway partial frames


async def ble_loop():
    global connection, tx_characteristic

    service = aioble.Service(_UART_SERVICE)
    rx = aioble.Characteristic(service, _UART_RX, write=True,
                               write_no_response=True, capture=True)
    tx = aioble.Characteristic(service, _UART_TX, notify=True)
    aioble.register_services(service)
    tx_characteristic = tx

    while True:
        print("advertising as", DEVICE_NAME)
        async with await aioble.advertise(
            _ADV_INTERVAL_US,
            name=DEVICE_NAME,
            services=[_UART_SERVICE],
        ) as conn:
            print("connected:", conn.device)
            connection = conn
            await send_obj({"evt": "hello", "fw": FIRMWARE_VERSION,
                            "name": DEVICE_NAME})
            reader = asyncio.create_task(rx_loop(rx))
            try:
                await conn.disconnected(timeout_ms=None)
            finally:
                reader.cancel()
                connection = None
                print("disconnected")


async def main():
    load_state()
    asyncio.create_task(scheduler_loop())
    asyncio.create_task(watcher_loop())
    await ble_loop()


asyncio.run(main())
