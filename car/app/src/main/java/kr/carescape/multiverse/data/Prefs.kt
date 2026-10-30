package kr.carescape.multiverse.data

import android.content.Context
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Snapshot
import org.json.JSONObject

/** Settings (E5) and the saved snapshot, kept in SharedPreferences. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("carescape", Context.MODE_PRIVATE)

    /** "llm" (the car's Pleos LLM) or "builtin" (the in-app generator). */
    var aiMode: String
        get() = p.getString("ai_mode", "llm")!!
        set(v) = p.edit().putString("ai_mode", v).apply()

    /** "auto" or a PlaceKind name. */
    var place: String
        get() = p.getString("place", "auto")!!
        set(v) = p.edit().putString("place", v).apply()

    var demo: Boolean
        get() = p.getBoolean("demo", false)
        set(v) = p.edit().putBoolean("demo", v).apply()

    var manualPass: Boolean
        get() = p.getBoolean("manual_pass", false)
        set(v) = p.edit().putBoolean("manual_pass", v).apply()

    var showTools: Boolean
        get() = p.getBoolean("show_tools", false)
        set(v) = p.edit().putBoolean("show_tools", v).apply()

    /**
     * The car's settings from before the game. Written as soon as it is taken and removed after restore,
     * so a crash mid-game still gets the car back to normal on the next launch.
     */
    var savedSnapshot: Snapshot?
        get() = p.getString("snapshot", null)?.let { runCatching { SnapshotJson.decode(it) }.getOrNull() }
        set(v) {
            if (v == null) p.edit().remove("snapshot").apply()
            else p.edit().putString("snapshot", SnapshotJson.encode(v)).apply()
        }
}

object SnapshotJson {
    private fun <T> JSONObject.seatMap(map: Map<Seat, T>) = JSONObject().also { o -> map.forEach { (k, v) -> o.put(k.name, v) } }

    fun encode(s: Snapshot): String = JSONObject().apply {
        put("doorLocked", seatMap(s.doorLocked))
        s.windowLocked?.let { put("windowLocked", it) }
        s.gloveBoxLocked?.let { put("gloveBoxLocked", it) }
        put("readingLight", seatMap(s.readingLight))
        s.cabinLightOn?.let { put("cabinLightOn", it) }
        put("ambientColor", seatMap(s.ambientColor))
        put("ambientBrightness", seatMap(s.ambientBrightness))
        put("seatVentilation", seatMap(s.seatVentilation))
        put("seatHeat", seatMap(s.seatHeat))
        s.hvacTempC?.let { put("hvacTempC", it.toDouble()) }
        s.fanSpeed?.let { put("fanSpeed", it) }
        s.recirculation?.let { put("recirculation", it) }
        s.airCleaner?.let { put("airCleaner", it) }
        s.smartGlassOpaque?.let { put("smartGlassOpaque", it) }
        s.etcsVolume?.let { put("etcsVolume", it) }
        put("takenAt", s.takenAt)
    }.toString()

    fun decode(text: String): Snapshot {
        val o = JSONObject(text)
        fun bools(key: String) = o.optJSONObject(key)?.let { m -> Seat.entries.filter { m.has(it.name) }.associateWith { m.getBoolean(it.name) } } ?: emptyMap()
        fun ints(key: String) = o.optJSONObject(key)?.let { m -> Seat.entries.filter { m.has(it.name) }.associateWith { m.getInt(it.name) } } ?: emptyMap()
        fun bool(key: String) = if (o.has(key)) o.getBoolean(key) else null
        fun int(key: String) = if (o.has(key)) o.getInt(key) else null
        return Snapshot(
            doorLocked = bools("doorLocked"), windowLocked = bool("windowLocked"), gloveBoxLocked = bool("gloveBoxLocked"),
            readingLight = bools("readingLight"), cabinLightOn = bool("cabinLightOn"),
            ambientColor = ints("ambientColor"), ambientBrightness = ints("ambientBrightness"),
            seatVentilation = ints("seatVentilation"), seatHeat = ints("seatHeat"),
            hvacTempC = if (o.has("hvacTempC")) o.getDouble("hvacTempC").toFloat() else null,
            fanSpeed = int("fanSpeed"), recirculation = bool("recirculation"), airCleaner = bool("airCleaner"),
            smartGlassOpaque = bool("smartGlassOpaque"), etcsVolume = int("etcsVolume"),
            takenAt = o.optLong("takenAt", System.currentTimeMillis())
        )
    }
}
