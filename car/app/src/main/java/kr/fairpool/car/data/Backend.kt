package kr.fairpool.car.data

import android.content.Context
import kr.fairpool.car.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A place as the server stores it: {label, area, address, lat, lng}. */
data class Place(val label: String, val lat: Double, val lng: Double, val address: String = "", val area: String = "") {
    fun json(): JSONObject = JSONObject()
        .put("label", label).put("lat", lat).put("lng", lng).put("address", address).put("area", area)

    companion object {
        fun from(o: JSONObject?): Place? {
            if (o == null || !o.has("lat") || o.isNull("lat")) return null
            return Place(
                o.optString("label", o.optString("name", "")), o.optDouble("lat"), o.optDouble("lng"),
                o.optString("address", ""), o.optString("area", "")
            )
        }
    }
}

/** App settings kept on the car. The vehicle secret identifies this car to the server. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("fairpool", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", BuildConfig.SERVER_URL)!!
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()
    var anonKey: String
        get() = prefs.getString("anon_key", BuildConfig.ANON_KEY)!!
        set(v) = prefs.edit().putString("anon_key", v).apply()
    var siteUrl: String
        get() = prefs.getString("site_url", BuildConfig.SITE_URL)!!
        set(v) = prefs.edit().putString("site_url", v.trimEnd('/')).apply()
    var vehicleSecret: String?
        get() = prefs.getString("vehicle_secret", null)
        set(v) = prefs.edit().putString("vehicle_secret", v).apply()
    var linkCode: String?
        get() = prefs.getString("link_code", null)
        set(v) = prefs.edit().putString("link_code", v).apply()
    /** true: the in-app demo backend answers instead of the server (no network needed). */
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(v) = prefs.edit().putBoolean("demo", v).apply()
    var fuelPrice: Int
        get() = prefs.getInt("fuel_price", 1700)
        set(v) = prefs.edit().putInt("fuel_price", v).apply()
}

class RpcException(val code: String) : Exception(code)

/** Calls the server functions: POST {server}/rest/v1/rpc/<fn> with a JSON body (PostgREST style). */
class Rpc(private val config: Config) {

    suspend fun call(fn: String, args: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        if (config.demo) return@withContext DemoBackend.call(fn, args)
        val conn = URL("${config.serverUrl}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 6000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (config.anonKey.isNotEmpty()) {
                conn.setRequestProperty("apikey", config.anonKey)
                conn.setRequestProperty("Authorization", "Bearer ${config.anonKey}")
            }
            conn.outputStream.use { it.write(args.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw RpcException(message?.takeIf { it.isNotEmpty() } ?: "http_$code")
            }
            when {
                body.isBlank() || body == "null" -> JSONObject()
                body.trimStart().startsWith("{") -> JSONObject(body)
                body.trimStart().startsWith("[") -> JSONObject().put("items", JSONArray(body))
                else -> JSONObject().put("value", body)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** True when the server answers at all (used to fall back to the demo backend). */
    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${config.serverUrl}/rest/v1/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.responseCode
            conn.disconnect()
            true
        } catch (e: IOException) {
            false
        }
    }
}

fun args(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}

/** Demo places for the Figma L3 scenario (MT 귀갓길), used by the demo backend and as fallbacks. */
object DemoPlaces {
    val school = Place("학교 정문", 37.5509, 126.9410, "서울 마포구 백범로 35", "마포구 신수동")
    val sinchon = Place("신촌역 2번 출구", 37.5552, 126.9369, "서울 서대문구 신촌로 90", "서대문구 창천동")
    val mangwon = Place("망원동", 37.5560, 126.9030, "서울 마포구 망원동", "마포구 망원동")
    val yeonnam = Place("연남동", 37.5660, 126.9230, "서울 마포구 연남동", "마포구 연남동")
    val yeonhui = Place("연희동 주민센터", 37.5700, 126.9330, "서울 서대문구 연희로 32", "서대문구 연희동")
    val eungam = Place("은평구 응암동", 37.5985, 126.9180, "서울 은평구 응암동", "은평구 응암동")
    val office = Place("거래처 (상암 DMC)", 37.5776, 126.8902, "서울 마포구 상암동", "마포구 상암동")
}

/**
 * In-app stand-in for the server so the car app can be shown end to end without a network.
 * Same function names and roughly the same response shapes as the Supabase functions.
 */
object DemoBackend {
    private val trips = mutableMapOf<String, JSONObject>()
    private val joinAt = mutableMapOf<String, Long>()
    private val folders = mutableListOf<JSONObject>()
    private val carpools = mutableListOf<JSONObject>()
    private var driver = JSONObject().put("name", "").put("phone4_set", false)
    private var bank: JSONObject? = null

    private fun code() = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
    private fun now() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA).format(Date())

    @Synchronized
    fun call(fn: String, a: JSONObject): JSONObject = when (fn) {
        "create_vehicle_link" -> JSONObject().put("vehicle_secret", "demo-" + UUID.randomUUID())
            .put("link_code", code()).put("expires_at", now())
        "get_vehicle_trips" -> JSONObject().put("linked", true).put("trips", JSONArray())
            .put("carpools", JSONArray(carpools))
        "get_driver_info" -> driver
        "set_driver_info" -> {
            driver = JSONObject().put("name", a.optString("p_name"))
                .put("phone4_set", driver.optBoolean("phone4_set") || a.optString("p_phone4").length == 4)
            driver
        }
        "get_bank_account" -> bank ?: JSONObject()
        "set_bank_account" -> {
            bank = if (a.optBoolean("p_consent")) JSONObject().put("bank_name", a.optString("p_bank_name"))
                .put("bank_account", a.optString("p_bank_account")).put("account_holder", a.optString("p_account_holder"))
            else null
            bank ?: JSONObject()
        }
        "create_trip" -> {
            val c = code()
            trips[c] = JSONObject().put("code", c).put("title", a.optString("p_title"))
                .put("origin", a.opt("p_origin")).put("destination", a.opt("p_destination"))
                .put("seats", a.optInt("p_seats", 3)).put("rule", a.optString("p_rule", "shapley"))
                .put("include_driver", a.optBoolean("p_include_driver", true)).put("status", "preparing")
                .put("passengers", JSONArray()).put("driver_stops", JSONArray()).put("created_at", now())
            joinAt[c] = System.currentTimeMillis()
            JSONObject().put("code", c).put("admin_secret", "demo-admin-$c").put("trip_id", c)
        }
        "get_trip_admin" -> tripAdmin(a.optString("p_code"))
        "set_driver_stops" -> trip(a).put("driver_stops", a.optJSONArray("p_stops") ?: JSONArray()).let { tripAdmin(it.optString("code")) }
        "set_trip_status" -> trip(a).put("status", a.optString("p_status")).let { JSONObject().put("ok", true) }
        "set_join_open", "update_progress", "queue_trip_share", "queue_batch_share", "confirm_records" ->
            JSONObject().put("ok", true)
        "remove_passenger" -> {
            val t = trip(a)
            val list = t.getJSONArray("passengers")
            val keep = JSONArray()
            for (i in 0 until list.length()) if (list.getJSONObject(i).optString("id") != a.optString("p_passenger_id")) keep.put(list.get(i))
            t.put("passengers", keep)
            tripAdmin(t.optString("code"))
        }
        "join_trip" -> {
            val t = trips[a.optString("p_code")] ?: throw RpcException("trip_not_found")
            val p = JSONObject().put("id", UUID.randomUUID().toString()).put("name", a.optString("p_name"))
                .put("pickup", a.opt("p_pickup")).put("dropoff", a.opt("p_dropoff"))
            t.getJSONArray("passengers").put(p)
            JSONObject().put("passenger_id", p.optString("id"))
        }
        "mark_passenger" -> {
            val p = passenger(a)
            if (a.has("p_seat") && !a.isNull("p_seat")) p.put("seat", a.optString("p_seat"))
            if (a.has("p_boarded") && !a.isNull("p_boarded")) {
                if (a.optBoolean("p_boarded")) p.put("boarded_at", now()) else p.remove("boarded_at").also { p.remove("alighted_at") }
            }
            if (a.has("p_alighted") && !a.isNull("p_alighted")) {
                if (a.optBoolean("p_alighted")) p.put("alighted_at", now()) else p.remove("alighted_at")
            }
            p
        }
        "post_settlement" -> trip(a).put("settlement", a.optJSONObject("p_payload")).put("status", "settled")
            .let { JSONObject().put("ok", true) }
        "choose_settlement" -> {
            val t = trip(a)
            t.put("settle_mode", a.optString("p_mode"))
            if (a.optString("p_mode") == "folder") {
                val f = a.optString("p_folder_code").takeIf { it.isNotEmpty() }?.let { folder(it) }
                    ?: newFolder(a.optString("p_new_folder", "카풀 폴더"))
                f.getJSONArray("open").put(t)
                t.put("folder", JSONObject().put("code", f.optString("code")).put("name", f.optString("name")))
            }
            JSONObject().put("ok", true)
        }
        "create_folder" -> folderSummary(newFolder(a.optString("p_name")))
        "list_folders" -> JSONObject().put("folders", JSONArray(folders.map { folderSummary(it) }))
        "get_folder_admin" -> folderAdmin(folder(a.optString("p_folder_code")))
        "request_batch" -> {
            val f = folder(a.optString("p_folder_code"))
            val payments = JSONArray()
            for ((name, amount) in peopleTotals(f.getJSONArray("open"))) {
                payments.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("amount", amount).put("paid", false))
            }
            f.put("requested", JSONObject().put("code", code()).put("payments", payments)).put("open", JSONArray())
            folderAdmin(f)
        }
        "mark_batch_paid" -> {
            val f = folder(a.optString("p_folder_code"))
            val pays = f.optJSONObject("requested")?.optJSONArray("payments") ?: JSONArray()
            for (i in 0 until pays.length()) {
                val p = pays.getJSONObject(i)
                if (p.optString("id") == a.optString("p_payment_id")) p.put("paid", a.optBoolean("p_paid", true))
            }
            folderAdmin(f)
        }
        "save_carpool" -> {
            val t = trip(a)
            carpools.add(JSONObject().put("code", code()).put("title", t.optString("title"))
                .put("members", t.getJSONArray("passengers").length()).put("created_at", now()))
            JSONObject().put("ok", true)
        }
        "mark_paid" -> passenger(a).put("paid", a.optBoolean("p_paid", true))
        else -> JSONObject().put("ok", true)
    }

    private fun trip(a: JSONObject) = trips[a.optString("p_code")] ?: throw RpcException("trip_not_found")

    private fun passenger(a: JSONObject): JSONObject {
        val list = trip(a).getJSONArray("passengers")
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            if (p.optString("id") == a.optString("p_passenger_id")) return p
        }
        throw RpcException("not_allowed")
    }

    /** Passengers "scan the QR" a few seconds apart so the L2 screen fills up during a demo. */
    private fun tripAdmin(code: String): JSONObject {
        val t = trips[code] ?: throw RpcException("trip_not_found")
        val list = t.getJSONArray("passengers")
        val elapsed = System.currentTimeMillis() - (joinAt[code] ?: 0)
        val demo = listOf(
            Triple("민지", DemoPlaces.school, DemoPlaces.mangwon),
            Triple("서연", DemoPlaces.school, DemoPlaces.yeonnam),
            Triple("태호", DemoPlaces.sinchon, DemoPlaces.yeonhui)
        )
        val wanted = minOf(t.optInt("seats", 3), (elapsed / 3000).toInt(), demo.size)
        if (t.optString("status") == "preparing") {
            while (list.length() < wanted) {
                val (name, pickup, dropoff) = demo[list.length()]
                list.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
                    .put("pickup", pickup.json()).put("dropoff", dropoff.json()))
            }
        }
        val waypoints = JSONArray()
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            p.optJSONObject("pickup")?.let { waypoints.put(JSONObject(it.toString()).put("kind", "pickup").put("passenger_id", p.optString("id")).put("name", p.optString("name"))) }
            p.optJSONObject("dropoff")?.let { waypoints.put(JSONObject(it.toString()).put("kind", "dropoff").put("passenger_id", p.optString("id")).put("name", p.optString("name"))) }
        }
        val out = JSONObject(t.toString())
        out.put("waypoints", waypoints)
        out.put("folders", JSONArray(folders.map { folderSummary(it) }))
        return out
    }

    private fun newFolder(name: String): JSONObject {
        val f = JSONObject().put("code", code()).put("name", name).put("open", JSONArray())
        folders.add(f)
        return f
    }

    private fun folder(code: String) = folders.firstOrNull { it.optString("code") == code } ?: throw RpcException("folder_not_found")

    private fun peopleTotals(open: JSONArray): Map<String, Long> {
        val totals = linkedMapOf<String, Long>()
        for (i in 0 until open.length()) {
            val shares = open.getJSONObject(i).optJSONObject("settlement")?.optJSONArray("shares") ?: continue
            for (j in 0 until shares.length()) {
                val s = shares.getJSONObject(j)
                if (s.optString("role") == "driver") continue
                totals[s.optString("name")] = (totals[s.optString("name")] ?: 0) + s.optLong("amount")
            }
        }
        return totals
    }

    private fun folderSummary(f: JSONObject): JSONObject {
        val open = f.getJSONArray("open")
        val status = if (f.has("requested") && open.length() == 0) "requested" else "open"
        return JSONObject().put("code", f.optString("code")).put("name", f.optString("name"))
            .put("status", status).put("trips", open.length()).put("amount", peopleTotals(open).values.sum())
    }

    private fun folderAdmin(f: JSONObject): JSONObject {
        val open = f.getJSONArray("open")
        val people = JSONArray()
        for ((name, amount) in peopleTotals(open)) people.put(JSONObject().put("name", name).put("amount", amount))
        val trips = JSONArray()
        for (i in 0 until open.length()) {
            val t = open.getJSONObject(i)
            trips.put(JSONObject().put("title", t.optString("title")).put("date", t.optString("created_at"))
                .put("total", t.optJSONObject("settlement")?.optLong("total") ?: 0))
        }
        return folderSummary(f).put("open", JSONObject().put("trips", trips).put("people", people))
            .put("requested", f.optJSONObject("requested") ?: JSONObject.NULL)
    }
}
