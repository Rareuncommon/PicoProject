package com.picolink.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picolink.app.ble.BleManager
import com.picolink.app.data.AppSettings
import com.picolink.app.data.SettingsRepository
import com.picolink.app.data.ThemeMode
import com.picolink.app.protocol.Protocol
import com.picolink.app.protocol.Protocol.Inbound
import com.picolink.app.protocol.Protocol.MacroStep
import com.picolink.app.protocol.Protocol.PinMode
import com.picolink.app.protocol.Protocol.PinState
import com.picolink.app.protocol.Protocol.ProgramInfo
import com.picolink.app.protocol.Protocol.Schedule
import com.picolink.app.protocol.Protocol.SysInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val ble = BleManager(application)
    private val settingsRepo = SettingsRepository(application)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // ------------------------------------------------------------------ state

    enum class LogKind { TX, RX, EVENT, INFO, ERROR }
    data class LogEntry(val timeMs: Long, val kind: LogKind, val text: String)

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _pins = MutableStateFlow<Map<Int, PinState>>(
        Protocol.GP_PINS.associateWith { PinState(pin = it) }
    )
    val pins: StateFlow<Map<Int, PinState>> = _pins.asStateFlow()

    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    private val _programs = MutableStateFlow<List<ProgramInfo>>(emptyList())
    val programs: StateFlow<List<ProgramInfo>> = _programs.asStateFlow()

    private val _sysInfo = MutableStateFlow<SysInfo?>(null)
    val sysInfo: StateFlow<SysInfo?> = _sysInfo.asStateFlow()

    private val _ledOn = MutableStateFlow(false)
    val ledOn: StateFlow<Boolean> = _ledOn.asStateFlow()

    private val _adcReadings = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val adcReadings: StateFlow<Map<Int, Float>> = _adcReadings.asStateFlow()

    /** Phone time minus Pico time, in seconds, measured at the last time sync/read. */
    private val _clockDriftS = MutableStateFlow<Long?>(null)
    val clockDriftS: StateFlow<Long?> = _clockDriftS.asStateFlow()

    private val _lastTimeSyncMs = MutableStateFlow<Long?>(null)
    val lastTimeSyncMs: StateFlow<Long?> = _lastTimeSyncMs.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    val terminalHistory = MutableStateFlow<List<String>>(emptyList())

    // ------------------------------------------------- request correlation

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (Inbound.Reply) -> Unit>()

    private var userInitiatedDisconnect = false
    private var reconnectJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            ble.incomingLines.collect { line -> handleLine(line) }
        }
        viewModelScope.launch {
            ble.transportLog.collect { addLog(LogKind.INFO, it) }
        }
        viewModelScope.launch {
            var previous = BleManager.ConnectionState.DISCONNECTED
            ble.connectionState.collect { state ->
                when (state) {
                    BleManager.ConnectionState.READY -> onLinkReady()
                    BleManager.ConnectionState.DISCONNECTED -> {
                        stopPolling()
                        pending.clear()
                        resetDeviceState()
                        if (previous != BleManager.ConnectionState.DISCONNECTED &&
                            !userInitiatedDisconnect
                        ) {
                            maybeAutoReconnect()
                        }
                    }
                    else -> Unit
                }
                previous = state
            }
        }
    }

    private fun resetDeviceState() {
        _pins.value = Protocol.GP_PINS.associateWith { PinState(pin = it) }
        _schedules.value = emptyList()
        _programs.value = emptyList()
        _sysInfo.value = null
        _ledOn.value = false
        _adcReadings.value = emptyMap()
        _clockDriftS.value = null
    }

    // ------------------------------------------------------------ connection

    fun connect(address: String, name: String?) {
        userInitiatedDisconnect = false
        reconnectJob?.cancel()
        viewModelScope.launch { settingsRepo.setLastDevice(address, name) }
        if (!ble.connect(address)) {
            addLog(LogKind.ERROR, "Invalid device address $address")
        }
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        ble.disconnect()
        addLog(LogKind.INFO, "Disconnected by user")
    }

    fun connectToLastDevice() {
        val address = settings.value.lastDeviceAddress ?: run {
            _toasts.tryEmit("No previously connected device")
            return
        }
        connect(address, settings.value.lastDeviceName)
    }

    private fun maybeAutoReconnect() {
        if (!settings.value.autoReconnect) return
        val address = settings.value.lastDeviceAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            for (attempt in 1..5) {
                val backoffMs = 1000L * (1 shl (attempt - 1))
                addLog(LogKind.INFO, "Reconnect attempt $attempt in ${backoffMs / 1000}s")
                delay(backoffMs)
                if (!isActive) return@launch
                if (ble.connectionState.value != BleManager.ConnectionState.DISCONNECTED) return@launch
                ble.connect(address)
                // Give the connection a chance to come up before the next attempt.
                delay(8000)
                if (ble.connectionState.value == BleManager.ConnectionState.READY) return@launch
                if (ble.connectionState.value != BleManager.ConnectionState.DISCONNECTED) {
                    ble.disconnect()
                }
            }
            addLog(LogKind.ERROR, "Auto-reconnect gave up after 5 attempts")
        }
    }

    private fun onLinkReady() {
        addLog(LogKind.INFO, "Connected to ${ble.connectedDevice.value?.address}")
        if (settings.value.autoTimeSync) syncTime()
        refreshAll()
        startPolling()
    }

    fun refreshAll() {
        request(Protocol.sysInfo(0).withFreshId()) { handleSysInfoReply(it) }
        refreshPins()
        refreshSchedules()
        refreshPrograms()
    }

    private fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(settings.value.statusPollSeconds.coerceAtLeast(2) * 1000L)
                if (ble.connectionState.value != BleManager.ConnectionState.READY) continue
                ble.requestRssi()
                request(Protocol.sysInfo(0).withFreshId()) { handleSysInfoReply(it) }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // -------------------------------------------------------------- requests

    /** Replaces the placeholder id (0) with a fresh correlation id. */
    private fun String.withFreshId(): Pair<Int, String> {
        val id = nextId.getAndIncrement()
        val o = JSONObject(this)
        o.put("id", id)
        return id to o.toString()
    }

    private fun request(idAndLine: Pair<Int, String>, onReply: ((Inbound.Reply) -> Unit)? = null) {
        val (id, line) = idAndLine
        if (onReply != null) pending[id] = onReply
        if (ble.sendLine(line)) {
            addLog(LogKind.TX, line)
        } else {
            pending.remove(id)
            addLog(LogKind.ERROR, "Not connected — dropped: $line")
        }
    }

    private fun handleLine(line: String) {
        when (val msg = Protocol.parse(line)) {
            is Inbound.Reply -> {
                addLog(if (msg.ok) LogKind.RX else LogKind.ERROR, line)
                if (!msg.ok && msg.error != null) _toasts.tryEmit("Pico: ${msg.error}")
                pending.remove(msg.id)?.invoke(msg)
            }
            is Inbound.Event -> {
                addLog(LogKind.EVENT, line)
                handleEvent(msg)
            }
            is Inbound.Unparsed -> addLog(LogKind.RX, line)
        }
    }

    private fun handleEvent(evt: Inbound.Event) {
        when (evt.name) {
            "hello" -> {
                // Firmware (re)announced itself, e.g. after a soft reset.
                if (settings.value.autoTimeSync) syncTime()
                refreshAll()
            }
            "pin" -> {
                val pin = evt.body.optInt("pin", -1)
                val value = evt.body.optInt("value", 0)
                if (pin >= 0) updatePin(pin) { it.copy(value = value) }
            }
            "sched_fired" -> {
                val name = evt.body.optString("name", "schedule")
                _toasts.tryEmit("Schedule fired: $name")
            }
            "prog_done" -> {
                val name = evt.body.optString("name", "program")
                _toasts.tryEmit("Program finished: $name")
            }
            "log" -> Unit // already captured in the log feed
        }
    }

    // ------------------------------------------------------------- time sync

    fun syncTime() {
        val nowMs = System.currentTimeMillis()
        val tzOffsetMin = TimeZone.getDefault().getOffset(nowMs) / 60000
        request(Protocol.timeSet(0, nowMs / 1000, tzOffsetMin).withFreshId()) { reply ->
            if (reply.ok) {
                _lastTimeSyncMs.value = System.currentTimeMillis()
                _clockDriftS.value = 0
                _toasts.tryEmit("Pico clock synchronized")
            }
        }
    }

    fun readPicoTime() {
        request(Protocol.timeGet(0).withFreshId()) { reply ->
            if (reply.ok) {
                val picoEpoch = reply.body.optLong("epoch", 0)
                if (picoEpoch > 0) {
                    _clockDriftS.value = System.currentTimeMillis() / 1000 - picoEpoch
                }
            }
        }
    }

    // ------------------------------------------------------------------ pins

    private fun updatePin(pin: Int, transform: (PinState) -> PinState) {
        _pins.update { map ->
            val current = map[pin] ?: PinState(pin = pin)
            map + (pin to transform(current))
        }
    }

    fun setPinMode(pin: Int, mode: PinMode) {
        if (mode == PinMode.PWM) {
            val state = _pins.value[pin] ?: PinState(pin = pin)
            setPwm(pin, state.pwmFreq, state.pwmDuty)
            return
        }
        request(Protocol.pinMode(0, pin, mode).withFreshId()) { reply ->
            if (reply.ok) {
                updatePin(pin) { it.copy(mode = mode, value = reply.body.optInt("value", 0)) }
            }
        }
    }

    fun writePin(pin: Int, value: Int) {
        request(Protocol.pinWrite(0, pin, value).withFreshId()) { reply ->
            if (reply.ok) updatePin(pin) { it.copy(mode = PinMode.OUT, value = value) }
        }
    }

    fun readPin(pin: Int) {
        request(Protocol.pinRead(0, pin).withFreshId()) { reply ->
            if (reply.ok) updatePin(pin) { it.copy(value = reply.body.optInt("value")) }
        }
    }

    fun refreshPins() {
        request(Protocol.pinReadAll(0).withFreshId()) { reply ->
            if (!reply.ok) return@request
            val arr = reply.body.optJSONArray("pins") ?: return@request
            _pins.update { map ->
                val updated = map.toMutableMap()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val pin = p.getInt("pin")
                    val prior = updated[pin] ?: PinState(pin = pin)
                    updated[pin] = prior.copy(
                        mode = PinMode.fromWire(p.optString("mode")),
                        value = if (p.has("value")) p.getInt("value") else null,
                        pwmFreq = p.optInt("freq", prior.pwmFreq),
                        pwmDuty = p.optDouble("duty", prior.pwmDuty.toDouble()).toFloat(),
                        watched = p.optBoolean("watched", false),
                    )
                }
                updated
            }
        }
    }

    fun setPwm(pin: Int, freq: Int, dutyPercent: Float) {
        request(Protocol.pwmSet(0, pin, freq, dutyPercent).withFreshId()) { reply ->
            if (reply.ok) {
                updatePin(pin) {
                    it.copy(mode = PinMode.PWM, pwmFreq = freq, pwmDuty = dutyPercent)
                }
            }
        }
    }

    fun stopPwm(pin: Int) {
        request(Protocol.pwmStop(0, pin).withFreshId()) { reply ->
            if (reply.ok) updatePin(pin) { it.copy(mode = PinMode.UNSET, pwmDuty = 0f) }
        }
    }

    fun readAdc(pin: Int) {
        request(Protocol.adcRead(0, pin).withFreshId()) { reply ->
            if (reply.ok) {
                val volts = reply.body.optDouble("volts", Double.NaN)
                if (!volts.isNaN()) {
                    _adcReadings.update { it + (pin to volts.toFloat()) }
                }
            }
        }
    }

    fun setWatch(pin: Int, watch: Boolean) {
        val builder = if (watch) Protocol.watchAdd(0, pin) else Protocol.watchRemove(0, pin)
        request(builder.withFreshId()) { reply ->
            if (reply.ok) updatePin(pin) { it.copy(watched = watch) }
        }
    }

    fun toggleLed() {
        val target = !_ledOn.value
        request(Protocol.ledSet(0, target).withFreshId()) { reply ->
            if (reply.ok) _ledOn.value = target
        }
    }

    // ------------------------------------------------------------- schedules

    fun refreshSchedules() {
        request(Protocol.schedList(0).withFreshId()) { reply ->
            if (!reply.ok) return@request
            val arr = reply.body.optJSONArray("schedules") ?: JSONArray()
            _schedules.value = (0 until arr.length())
                .mapNotNull { runCatching { Schedule.fromJson(arr.getJSONObject(it)) }.getOrNull() }
                .sortedWith(compareBy({ it.hour }, { it.minute }))
        }
    }

    fun saveSchedule(schedule: Schedule) {
        request(Protocol.schedAdd(0, schedule).withFreshId()) { reply ->
            if (reply.ok) {
                _toasts.tryEmit("Schedule saved")
                refreshSchedules()
            }
        }
    }

    fun deleteSchedule(schedId: Int) {
        request(Protocol.schedDelete(0, schedId).withFreshId()) { reply ->
            if (reply.ok) refreshSchedules()
        }
    }

    fun setScheduleEnabled(schedId: Int, enabled: Boolean) {
        request(Protocol.schedSetEnabled(0, schedId, enabled).withFreshId()) { reply ->
            if (reply.ok) {
                _schedules.update { list ->
                    list.map { if (it.id == schedId) it.copy(enabled = enabled) else it }
                }
            }
        }
    }

    // -------------------------------------------------------------- programs

    fun refreshPrograms() {
        request(Protocol.progList(0).withFreshId()) { reply ->
            if (!reply.ok) return@request
            val arr = reply.body.optJSONArray("programs") ?: JSONArray()
            _programs.value = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val stepsArr = o.optJSONArray("steps") ?: JSONArray()
                    ProgramInfo(
                        name = o.getString("name"),
                        builtin = o.optBoolean("builtin", false),
                        steps = (0 until stepsArr.length()).map {
                            MacroStep.fromJson(stepsArr.getJSONObject(it))
                        },
                    )
                }.getOrNull()
            }.sortedWith(compareBy({ !it.builtin }, { it.name }))
        }
    }

    fun runProgram(name: String) {
        request(Protocol.progRun(0, name).withFreshId()) { reply ->
            if (reply.ok) _toasts.tryEmit("Running \"$name\"")
        }
    }

    fun stopProgram() {
        request(Protocol.progStop(0).withFreshId()) { reply ->
            if (reply.ok) _toasts.tryEmit("Program stopped")
        }
    }

    fun saveMacro(name: String, steps: List<MacroStep>) {
        request(Protocol.progSave(0, name, steps).withFreshId()) { reply ->
            if (reply.ok) {
                _toasts.tryEmit("Program \"$name\" saved to Pico")
                refreshPrograms()
            }
        }
    }

    fun deleteMacro(name: String) {
        request(Protocol.progDelete(0, name).withFreshId()) { reply ->
            if (reply.ok) refreshPrograms()
        }
    }

    // -------------------------------------------------------------- terminal

    /**
     * Sends a terminal entry. Raw JSON is passed through; anything else goes
     * through a small shorthand dictionary (see TerminalScreen for the list).
     */
    fun sendTerminalCommand(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        terminalHistory.update { (it + text).takeLast(50) }

        if (text.startsWith("{")) {
            val withId = runCatching {
                val o = JSONObject(text)
                if (!o.has("id")) o.put("id", nextId.getAndIncrement())
                o.toString()
            }.getOrNull()
            if (withId == null) {
                addLog(LogKind.ERROR, "Invalid JSON: $text")
                return
            }
            if (ble.sendLine(withId)) addLog(LogKind.TX, withId)
            else addLog(LogKind.ERROR, "Not connected")
            return
        }

        val parts = text.split(Regex("\\s+"))
        val line: Pair<Int, String>? = when (parts[0].lowercase()) {
            "ping" -> Protocol.ping(0).withFreshId()
            "info" -> Protocol.sysInfo(0).withFreshId()
            "temp" -> Protocol.tempRead(0).withFreshId()
            "time" -> Protocol.timeGet(0).withFreshId()
            "sync" -> { syncTime(); return }
            "reset" -> Protocol.reset(0).withFreshId()
            "led" -> parts.getOrNull(1)?.let {
                Protocol.ledSet(0, it == "on" || it == "1").withFreshId()
            }
            "read" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                Protocol.pinRead(0, it).withFreshId()
            }
            "write" -> {
                val pin = parts.getOrNull(1)?.toIntOrNull()
                val value = parts.getOrNull(2)?.toIntOrNull()
                if (pin != null && value != null) Protocol.pinWrite(0, pin, value).withFreshId()
                else null
            }
            "adc" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                Protocol.adcRead(0, it).withFreshId()
            }
            "run" -> parts.getOrNull(1)?.let { Protocol.progRun(0, it).withFreshId() }
            else -> null
        }
        if (line == null) {
            addLog(LogKind.ERROR, "Unknown command: $text (try: ping, info, temp, time, sync, led on|off, read <pin>, write <pin> <0|1>, adc <pin>, run <prog>, reset, or raw JSON)")
            return
        }
        request(line)
    }

    // ------------------------------------------------------------------- log

    private fun addLog(kind: LogKind, text: String) {
        _log.update { (it + LogEntry(System.currentTimeMillis(), kind, text)).takeLast(500) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun exportLogText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _log.value.joinToString("\n") {
            "${fmt.format(Date(it.timeMs))} [${it.kind}] ${it.text}"
        }
    }

    // -------------------------------------------------------------- settings

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(v) }
    fun setAutoConnectOnLaunch(v: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoConnectOnLaunch(v) }
    fun setAutoReconnect(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoReconnect(v) }
    fun setAutoTimeSync(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoTimeSync(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { settingsRepo.setKeepScreenOn(v) }
    fun setStatusPollSeconds(s: Int) =
        viewModelScope.launch { settingsRepo.setStatusPollSeconds(s) }
    fun setHapticFeedback(v: Boolean) =
        viewModelScope.launch { settingsRepo.setHapticFeedback(v) }

    private fun handleSysInfoReply(reply: Inbound.Reply) {
        if (!reply.ok) return
        _sysInfo.value = SysInfo(
            firmware = reply.body.optString("fw", "?"),
            uptimeS = reply.body.optLong("uptime", 0),
            freeMemBytes = reply.body.optLong("mem_free", 0),
            cpuFreqHz = reply.body.optLong("cpu_freq", 0),
            picoEpoch = reply.body.optLong("epoch", 0).takeIf { it > 0 },
            tempC = reply.body.optDouble("temp", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
        )
        _ledOn.value = reply.body.optInt("led", 0) == 1
        _sysInfo.value?.picoEpoch?.let {
            _clockDriftS.value = System.currentTimeMillis() / 1000 - it
        }
    }

    override fun onCleared() {
        ble.stopScan()
        ble.disconnect()
        super.onCleared()
    }
}
