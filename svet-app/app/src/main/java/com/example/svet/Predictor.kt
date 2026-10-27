package com.example.svet

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

object Fmt {
    fun dur(totalMin: Long): String {
        val m = max(0L, totalMin)
        val h = m / 60
        val mm = m % 60
        return when {
            h == 0L -> "$mm мин"
            mm == 0L -> "$h ч"
            else -> "$h ч $mm мин"
        }
    }

    fun stamp(ts: Long): String =
        SimpleDateFormat("EEE dd.MM HH:mm", Locale.getDefault()).format(Date(ts))

    fun clock(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    fun clockSec(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

/**
 * Прогноз по журналу.
 *
 * Свет есть  -> оцениваем, когда вероятнее всего его выключат (по дню недели и времени суток
 *               прошлых отключений; свежие записи весят больше).
 * Света нет  -> оцениваем, когда включат (по длительности прошлых отключений).
 */
object Predictor {
    private const val HORIZON_MIN = 180
    private const val STEP_MIN = 5
    private const val SIGMA_MIN = 30.0
    private const val TAU_DAYS = 21.0
    private const val LOOKBACK_DAYS = 90
    private const val OTHER_DAY_WEIGHT = 0.35
    private const val DAY_MS = 86_400_000L
    private const val MIN_OFF_EVENTS = 3

    fun summary(events: List<Event>, now: Long): String {
        val sorted = events.sortedBy { it.ts }
        return when (sorted.lastOrNull()?.on) {
            null -> "Журнал пуст. Отмечай, когда свет выключили и включили, — через несколько записей появится прогноз."
            true -> predictOutage(sorted, now)
            false -> predictReturn(sorted, now)
        }
    }

    // ---------- когда выключат ----------

    private class Pt(val w: Double, val dow: Int, val minute: Int)

    private fun predictOutage(ev: List<Event>, now: Long): String {
        val from = now - LOOKBACK_DAYS * DAY_MS
        val offs = ev.filter { !it.on && it.ts in from..now }
        if (offs.size < MIN_OFF_EVENTS) {
            return "Пока мало данных: отключений в журнале ${offs.size}. " +
                "Нужно хотя бы $MIN_OFF_EVENTS–5, чтобы строить прогноз."
        }

        val daysObserved = max(1, min(LOOKBACK_DAYS, ceil((now - ev.first().ts).toDouble() / DAY_MS).toInt()))

        val pts = offs.map { e ->
            val c = Calendar.getInstance().apply { timeInMillis = e.ts }
            Pt(
                w = exp(-((now - e.ts).toDouble() / DAY_MS) / TAU_DAYS),
                dow = c.get(Calendar.DAY_OF_WEEK),
                minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            )
        }

        // Дни, по которым мы «наблюдали»: их вес и день недели (знаменатель частоты).
        val pastDays = (0 until daysObserved).map { k ->
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, -k)
            }
            Pair(exp(-k / TAU_DAYS), c.get(Calendar.DAY_OF_WEEK))
        }

        val n = HORIZON_MIN / STEP_MIN
        val lam = DoubleArray(n) // ожидаемое число отключений в минуту
        for (i in 0 until n) {
            val t = now + (i * STEP_MIN + STEP_MIN / 2) * 60_000L
            val tc = Calendar.getInstance().apply { timeInMillis = t }
            val tDow = tc.get(Calendar.DAY_OF_WEEK)
            val tMin = tc.get(Calendar.HOUR_OF_DAY) * 60 + tc.get(Calendar.MINUTE)

            var dens = 0.0
            for (p in pts) {
                val f = if (p.dow == tDow) 1.0 else OTHER_DAY_WEIGHT
                dens += p.w * f * gauss(circDiff(tMin, p.minute))
            }
            var denom = 0.0
            for ((w, dow) in pastDays) {
                denom += w * (if (dow == tDow) 1.0 else OTHER_DAY_WEIGHT)
            }
            lam[i] = if (denom > 0) dens / denom else 0.0
        }

        val total = lam.sum() * STEP_MIN
        val p = 1 - exp(-total)
        val pct = (p * 100).roundToInt()
        val conf = when {
            offs.size < 8 -> "точность низкая"
            offs.size < 20 -> "точность средняя"
            else -> "точность хорошая"
        }

        if (p < 0.15) {
            return "В ближайшие 3 часа отключение маловероятно (≈$pct%, $conf)."
        }

        var cum = 0.0
        var lo = -1
        var hi = n - 1
        for (i in 0 until n) {
            cum += lam[i] * STEP_MIN
            if (lo < 0 && cum >= 0.2 * total) lo = i
            if (cum >= 0.8 * total) {
                hi = i
                break
            }
        }
        if (lo < 0) lo = 0
        val fromMin = lo * STEP_MIN
        val toMin = (hi + 1) * STEP_MIN

        val when_ = if (fromMin == 0) "в ближайшие $toMin мин" else "через $fromMin–$toMin мин"
        return "Свет могут отключить $when_ (вероятность за 3 часа ≈ $pct%, $conf)."
    }

    private fun gauss(x: Int): Double {
        val z = x / SIGMA_MIN
        return exp(-0.5 * z * z) / (SIGMA_MIN * sqrt(2 * PI))
    }

    private fun circDiff(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 1440 - d)
    }

    // ---------- когда включат ----------

    private fun predictReturn(ev: List<Event>, now: Long): String {
        val last = ev.last()
        val elapsedMin = (now - last.ts) / 60_000.0
        val head = "Света нет уже ${Fmt.dur(elapsedMin.toLong())}."

        val durs = ArrayList<Double>()
        for (i in 0 until ev.size - 1) {
            if (!ev[i].on && ev[i + 1].on) {
                durs.add((ev[i + 1].ts - ev[i].ts) / 60_000.0)
            }
        }
        if (durs.size < 2) {
            return "$head Пока мало данных, чтобы сказать, когда включат " +
                "(нужно хотя бы 2 завершённых отключения)."
        }
        durs.sort()

        val typical = "Обычно отключения длятся " +
            "${Fmt.dur(percentile(durs, 0.25).roundToLong())}–${Fmt.dur(percentile(durs, 0.75).roundToLong())} " +
            "(медиана ${Fmt.dur(percentile(durs, 0.5).roundToLong())})."

        val remaining = durs.filter { it > elapsedMin }.map { it - elapsedMin }.sorted()
        return if (remaining.size >= 2) {
            val a = round5(percentile(remaining, 0.25))
            val b = round5(percentile(remaining, 0.75))
            val range = if (a == b) "около $a мин" else "через $a–$b мин"
            "$head Скорее всего включат $range. $typical"
        } else {
            "$head Это уже дольше обычного. $typical"
        }
    }

    private fun round5(x: Double): Long = max(5L, (x / 5).roundToLong() * 5)

    /** s — отсортирован по возрастанию. */
    private fun percentile(s: List<Double>, q: Double): Double {
        if (s.size == 1) return s[0]
        val pos = q * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
}
