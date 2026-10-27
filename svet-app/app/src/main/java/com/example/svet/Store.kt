package com.example.svet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Одна запись журнала: свет включили (on = true) или выключили (on = false). */
data class Event(
    val id: Long,
    val ts: Long,
    val on: Boolean,
    val auto: Boolean = false,
    val note: String = ""
)

/** Журнал событий хранится в SharedPreferences как JSON. */
object Store {
    private const val FILE = "svet"
    private const val KEY_EVENTS = "events"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context): List<Event> {
        val raw = prefs(c).getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Event(
                    id = o.getLong("id"),
                    ts = o.getLong("ts"),
                    on = o.getBoolean("on"),
                    auto = o.optBoolean("auto", false),
                    note = o.optString("note", "")
                )
            }.sortedBy { it.ts }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(c: Context, list: List<Event>) {
        val arr = JSONArray()
        list.sortedBy { it.ts }.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("ts", e.ts)
                    .put("on", e.on)
                    .put("auto", e.auto)
                    .put("note", e.note)
            )
        }
        prefs(c).edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    @Synchronized
    fun add(c: Context, ts: Long, on: Boolean, auto: Boolean = false, note: String = ""): Event {
        val list = load(c).toMutableList()
        var id = System.currentTimeMillis()
        while (list.any { it.id == id }) id++
        val e = Event(id, ts, on, auto, note)
        list.add(e)
        save(c, list)
        return e
    }

    @Synchronized
    fun update(c: Context, id: Long, newTs: Long?, newOn: Boolean?) {
        val list = load(c).map {
            if (it.id == id) it.copy(ts = newTs ?: it.ts, on = newOn ?: it.on) else it
        }
        save(c, list)
    }

    @Synchronized
    fun delete(c: Context, id: Long) {
        save(c, load(c).filter { it.id != id })
    }

    @Synchronized
    fun clear(c: Context) {
        save(c, emptyList())
    }

    /** true — свет есть, false — света нет, null — журнал пуст. */
    fun isPowerOn(c: Context): Boolean? = load(c).lastOrNull()?.on
}

/** Настройки и состояние режима «Ждать свет». */
object Prefs {
    private fun p(c: Context) =
        c.applicationContext.getSharedPreferences("svet_settings", Context.MODE_PRIVATE)

    /** Сколько секунд обычно проходит от появления света до подключения телефона к Wi-Fi. */
    fun routerDelaySec(c: Context): Int = p(c).getInt("router_delay", 60)
    fun setRouterDelaySec(c: Context, v: Int) = p(c).edit().putInt("router_delay", v).apply()

    /** Считать подключение зарядки сигналом «свет дали». */
    fun chargerSignal(c: Context): Boolean = p(c).getBoolean("charger_signal", false)
    fun setChargerSignal(c: Context, v: Boolean) = p(c).edit().putBoolean("charger_signal", v).apply()

    fun waitActive(c: Context): Boolean = p(c).getBoolean("wait_active", false)
    fun setWaitActive(c: Context, v: Boolean) = p(c).edit().putBoolean("wait_active", v).apply()

    fun sawDown(c: Context): Boolean = p(c).getBoolean("saw_down", true)
    fun setSawDown(c: Context, v: Boolean) = p(c).edit().putBoolean("saw_down", v).apply()

    fun chargerArmed(c: Context): Boolean = p(c).getBoolean("charger_armed", false)
    fun setChargerArmed(c: Context, v: Boolean) = p(c).edit().putBoolean("charger_armed", v).apply()

    fun beginWait(c: Context, sawDown: Boolean, chargerArmed: Boolean) {
        p(c).edit()
            .putBoolean("wait_active", true)
            .putBoolean("saw_down", sawDown)
            .putBoolean("charger_armed", chargerArmed)
            .apply()
    }
}
