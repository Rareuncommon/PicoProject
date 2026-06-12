package com.picolink.app.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-lines protocol shared with the Pico W firmware (see /pico/main.py).
 *
 * Requests:  {"cmd":"pin.write","id":7,"pin":15,"value":1}
 * Replies:   {"ok":true,"id":7, ...payload}  /  {"ok":false,"id":7,"err":"..."}
 * Events:    {"evt":"pin","pin":15,"value":1}  (unsolicited pushes)
 */
object Protocol {

    // ------------------------------------------------------------- pin models

    enum class PinMode(val wire: String, val label: String) {
        OUT("out", "Output"),
        IN("in", "Input"),
        IN_PULLUP("in_pu", "Input · pull-up"),
        IN_PULLDOWN("in_pd", "Input · pull-down"),
        PWM("pwm", "PWM"),
        UNSET("unset", "Not configured");

        companion object {
            fun fromWire(s: String?): PinMode = entries.firstOrNull { it.wire == s } ?: UNSET
        }
    }

    data class PinState(
        val pin: Int,
        val mode: PinMode = PinMode.UNSET,
        val value: Int? = null,
        val pwmFreq: Int = 1000,
        val pwmDuty: Float = 0f,
        val watched: Boolean = false,
    )

    /** GP pins that are safe to expose on a Pico W (GP23–25, 29 are internal). */
    val GP_PINS: List<Int> = (0..22).toList() + listOf(26, 27, 28)
    val ADC_PINS: Set<Int> = setOf(26, 27, 28)

    // -------------------------------------------------------- schedule models

    data class ScheduleAction(
        val type: String,           // "pin" | "pwm" | "prog"
        val pin: Int? = null,
        val value: Int? = null,
        val freq: Int? = null,
        val duty: Float? = null,
        val prog: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            pin?.let { put("pin", it) }
            value?.let { put("value", it) }
            freq?.let { put("freq", it) }
            duty?.let { put("duty", it.toDouble()) }
            prog?.let { put("prog", it) }
        }

        fun describe(): String = when (type) {
            "pin" -> "Set GP$pin ${if (value == 1) "HIGH" else "LOW"}"
            "pwm" -> "PWM GP$pin @ ${freq}Hz, ${duty}%"
            "prog" -> "Run program \"$prog\""
            else -> type
        }

        companion object {
            fun fromJson(o: JSONObject) = ScheduleAction(
                type = o.optString("type"),
                pin = if (o.has("pin")) o.getInt("pin") else null,
                value = if (o.has("value")) o.getInt("value") else null,
                freq = if (o.has("freq")) o.getInt("freq") else null,
                duty = if (o.has("duty")) o.getDouble("duty").toFloat() else null,
                prog = if (o.has("prog")) o.getString("prog") else null,
            )
        }
    }

    data class Schedule(
        val id: Int,
        val name: String,
        val hour: Int,
        val minute: Int,
        /** Days of week the schedule fires on; 0 = Monday … 6 = Sunday. */
        val days: Set<Int>,
        val enabled: Boolean,
        val action: ScheduleAction,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("hh", hour)
            put("mm", minute)
            put("days", JSONArray(days.sorted()))
            put("enabled", enabled)
            put("action", action.toJson())
        }

        companion object {
            val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

            fun fromJson(o: JSONObject): Schedule {
                val daysArr = o.optJSONArray("days") ?: JSONArray()
                return Schedule(
                    id = o.getInt("id"),
                    name = o.optString("name", "Schedule"),
                    hour = o.getInt("hh"),
                    minute = o.getInt("mm"),
                    days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet(),
                    enabled = o.optBoolean("enabled", true),
                    action = ScheduleAction.fromJson(o.getJSONObject("action")),
                )
            }
        }
    }

    // --------------------------------------------------------- program models

    data class MacroStep(
        val op: String,             // "pin" | "pwm" | "sleep"
        val pin: Int? = null,
        val value: Int? = null,
        val freq: Int? = null,
        val duty: Float? = null,
        val ms: Int? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("op", op)
            pin?.let { put("pin", it) }
            value?.let { put("value", it) }
            freq?.let { put("freq", it) }
            duty?.let { put("duty", it.toDouble()) }
            ms?.let { put("ms", it) }
        }

        fun describe(): String = when (op) {
            "pin" -> "GP$pin → ${if (value == 1) "HIGH" else "LOW"}"
            "pwm" -> "PWM GP$pin @ ${freq}Hz, ${duty}%"
            "sleep" -> "Wait ${ms}ms"
            else -> op
        }

        companion object {
            fun fromJson(o: JSONObject) = MacroStep(
                op = o.optString("op"),
                pin = if (o.has("pin")) o.getInt("pin") else null,
                value = if (o.has("value")) o.getInt("value") else null,
                freq = if (o.has("freq")) o.getInt("freq") else null,
                duty = if (o.has("duty")) o.getDouble("duty").toFloat() else null,
                ms = if (o.has("ms")) o.getInt("ms") else null,
            )
        }
    }

    data class ProgramInfo(val name: String, val builtin: Boolean, val steps: List<MacroStep>)

    data class SysInfo(
        val firmware: String,
        val uptimeS: Long,
        val freeMemBytes: Long,
        val cpuFreqHz: Long,
        val picoEpoch: Long?,
        val tempC: Float?,
    )

    // ------------------------------------------------------- command builders

    private fun cmd(name: String, id: Int, vararg pairs: Pair<String, Any>): String =
        JSONObject().apply {
            put("cmd", name)
            put("id", id)
            pairs.forEach { (k, v) -> put(k, v) }
        }.toString()

    fun ping(id: Int) = cmd("ping", id)
    fun sysInfo(id: Int) = cmd("sys.info", id)
    fun reset(id: Int) = cmd("reset", id)

    fun timeSet(id: Int, epochSeconds: Long, tzOffsetMinutes: Int) =
        cmd("time.set", id, "epoch" to epochSeconds, "tz" to tzOffsetMinutes)

    fun timeGet(id: Int) = cmd("time.get", id)

    fun pinMode(id: Int, pin: Int, mode: PinMode) =
        cmd("pin.mode", id, "pin" to pin, "mode" to mode.wire)

    fun pinWrite(id: Int, pin: Int, value: Int) =
        cmd("pin.write", id, "pin" to pin, "value" to value)

    fun pinRead(id: Int, pin: Int) = cmd("pin.read", id, "pin" to pin)
    fun pinReadAll(id: Int) = cmd("pin.read_all", id)

    fun pwmSet(id: Int, pin: Int, freq: Int, dutyPercent: Float) =
        cmd("pwm.set", id, "pin" to pin, "freq" to freq, "duty" to dutyPercent.toDouble())

    fun pwmStop(id: Int, pin: Int) = cmd("pwm.stop", id, "pin" to pin)

    fun adcRead(id: Int, pin: Int) = cmd("adc.read", id, "pin" to pin)
    fun tempRead(id: Int) = cmd("temp.read", id)
    fun ledSet(id: Int, on: Boolean) = cmd("led", id, "value" to if (on) 1 else 0)

    fun watchAdd(id: Int, pin: Int) = cmd("watch.add", id, "pin" to pin)
    fun watchRemove(id: Int, pin: Int) = cmd("watch.del", id, "pin" to pin)

    fun schedList(id: Int) = cmd("sched.list", id)
    fun schedAdd(id: Int, s: Schedule) = cmd("sched.add", id, "sched" to s.toJson())
    fun schedDelete(id: Int, schedId: Int) = cmd("sched.del", id, "sid" to schedId)
    fun schedSetEnabled(id: Int, schedId: Int, enabled: Boolean) =
        cmd("sched.enable", id, "sid" to schedId, "enabled" to enabled)

    fun progList(id: Int) = cmd("prog.list", id)
    fun progRun(id: Int, name: String) = cmd("prog.run", id, "name" to name)
    fun progStop(id: Int) = cmd("prog.stop", id)
    fun progSave(id: Int, name: String, steps: List<MacroStep>) =
        cmd("prog.save", id, "name" to name, "steps" to JSONArray(steps.map { it.toJson() }))
    fun progDelete(id: Int, name: String) = cmd("prog.del", id, "name" to name)

    // --------------------------------------------------------------- inbound

    sealed interface Inbound {
        data class Reply(val id: Int, val ok: Boolean, val error: String?, val body: JSONObject) : Inbound
        data class Event(val name: String, val body: JSONObject) : Inbound
        data class Unparsed(val raw: String) : Inbound
    }

    fun parse(line: String): Inbound {
        val o = try {
            JSONObject(line)
        } catch (_: Exception) {
            return Inbound.Unparsed(line)
        }
        return when {
            o.has("evt") -> Inbound.Event(o.getString("evt"), o)
            o.has("ok") -> Inbound.Reply(
                id = o.optInt("id", -1),
                ok = o.getBoolean("ok"),
                error = if (o.has("err")) o.getString("err") else null,
                body = o,
            )
            else -> Inbound.Unparsed(line)
        }
    }
}
