package com.example.svet

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private val green = Color.rgb(46, 125, 50)
    private val red = Color.rgb(183, 28, 28)
    private val greenText = Color.rgb(102, 220, 120)
    private val redText = Color.rgb(255, 110, 100)
    private val grey = Color.rgb(160, 160, 160)

    private lateinit var stateTv: TextView
    private lateinit var sinceTv: TextView
    private lateinit var predTv: TextView
    private lateinit var waitBtn: Button
    private lateinit var listView: ListView

    private val adapter = LogAdapter()
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(14), dp(14), dp(14), dp(6))
        }

        stateTv = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        sinceTv = TextView(this).apply {
            textSize = 15f
            setTextColor(grey)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(stateTv)
        root.addView(sinceTv)

        // Две большие кнопки. Долгое нажатие — отметить задним числом.
        val offBtn = bigButton("🌑\nСвета нет", red)
        val onBtn = bigButton("💡\nСвет есть", green)
        offBtn.setOnClickListener { markNow(false) }
        onBtn.setOnClickListener { markNow(true) }
        offBtn.setOnLongClickListener { markAt(false); true }
        onBtn.setOnLongClickListener { markAt(true); true }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(offBtn, LinearLayout.LayoutParams(0, dp(100), 1f).apply { rightMargin = dp(6) })
        row.addView(onBtn, LinearLayout.LayoutParams(0, dp(100), 1f).apply { leftMargin = dp(6) })
        root.addView(row)

        val hint = TextView(this).apply {
            text = "Долгое нажатие на кнопку — отметить с другим временем"
            textSize = 11f
            setTextColor(Color.rgb(110, 110, 110))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(hint)

        predTv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(24, 24, 28))
            }
        }
        root.addView(predTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        // Ждать свет / Чёрный экран / Настройки
        waitBtn = smallButton("⏰ Ждать свет", Color.rgb(40, 53, 147))
        val blackBtn = smallButton("⬛ Чёрный экран", Color.rgb(50, 50, 50))
        val setBtn = smallButton("⚙", Color.rgb(50, 50, 50))
        waitBtn.setOnClickListener { onWaitClick() }
        blackBtn.setOnClickListener { startActivity(Intent(this, BlackActivity::class.java)) }
        setBtn.setOnClickListener { showSettings() }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(waitBtn, LinearLayout.LayoutParams(0, dp(52), 2f).apply { rightMargin = dp(6) })
        row2.addView(blackBtn, LinearLayout.LayoutParams(0, dp(52), 2f).apply { rightMargin = dp(6) })
        row2.addView(setBtn, LinearLayout.LayoutParams(0, dp(52), 0.8f))
        root.addView(row2)

        val logTitle = TextView(this).apply {
            text = "Журнал (нажми на запись, чтобы исправить)"
            textSize = 13f
            setTextColor(grey)
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(logTitle)

        listView = ListView(this).apply {
            divider = ColorDrawable(Color.rgb(34, 34, 34))
            dividerHeight = 1
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> editEvent(this@MainActivity.adapter.items[pos]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- обновление экрана ----------

    private fun refresh() {
        val ev = Store.load(this)
        val now = System.currentTimeMillis()
        val last = ev.lastOrNull()

        if (last == null) {
            stateTv.text = "Нет данных"
            stateTv.setTextColor(grey)
            sinceTv.text = "Нажми, что сейчас со светом"
        } else if (last.on) {
            stateTv.text = "💡 Свет есть"
            stateTv.setTextColor(greenText)
            sinceTv.text = "уже ${Fmt.dur((now - last.ts) / 60_000)} (с ${Fmt.stamp(last.ts)})"
        } else {
            stateTv.text = "🌑 Света нет"
            stateTv.setTextColor(redText)
            sinceTv.text = "уже ${Fmt.dur((now - last.ts) / 60_000)} (с ${Fmt.stamp(last.ts)})"
        }

        predTv.text = Predictor.summary(ev, now)
        waitBtn.text = if (Prefs.waitActive(this)) "⏹ Не ждать свет" else "⏰ Ждать свет"
        adapter.items = ev.sortedByDescending { it.ts }
        adapter.notifyDataSetChanged()
    }

    // ---------- отметки ----------

    private fun markNow(on: Boolean) {
        if (Store.isPowerOn(this) == on) {
            toast(if (on) "Уже отмечено: свет есть" else "Уже отмечено: света нет")
            return
        }
        Store.add(this, System.currentTimeMillis(), on)
        if (on && Prefs.waitActive(this)) stopWait()
        refresh()
    }

    private fun markAt(on: Boolean) {
        pickDateTime(System.currentTimeMillis()) { ts ->
            Store.add(this, ts, on)
            refresh()
        }
    }

    private fun editEvent(e: Event) {
        val toggle = if (e.on) "Исправить на «света нет»" else "Исправить на «свет есть»"
        AlertDialog.Builder(this)
            .setTitle("${if (e.on) "Свет есть" else "Света нет"} · ${Fmt.stamp(e.ts)}")
            .setItems(arrayOf("Изменить время", toggle, "Удалить")) { _, i ->
                when (i) {
                    0 -> pickDateTime(e.ts) { ts ->
                        Store.update(this, e.id, ts, null)
                        refresh()
                    }
                    1 -> {
                        Store.update(this, e.id, null, !e.on)
                        refresh()
                    }
                    2 -> {
                        Store.delete(this, e.id)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun pickDateTime(initial: Long, done: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                TimePickerDialog(
                    this,
                    { _, h, min ->
                        val r = Calendar.getInstance().apply {
                            set(y, m, d, h, min, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        done(r.timeInMillis)
                    },
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
                ).show()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ---------- режим «Ждать свет» ----------

    private fun onWaitClick() {
        if (Prefs.waitActive(this)) {
            stopWait()
            refresh()
            return
        }
        if (Store.isPowerOn(this) == false) {
            startWait()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Сейчас отмечено «свет есть»")
                .setMessage("Отметить «света нет» и начать ждать, пока он появится?")
                .setPositiveButton("Да") { _, _ ->
                    Store.add(this, System.currentTimeMillis(), false)
                    startWait()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun startWait() {
        askBatteryWhitelist()
        startForegroundService(Intent(this, WatchService::class.java))
        toast("Жду свет. Когда появится интернет по Wi‑Fi — зазвонит будильник")
        ui.postDelayed({ refresh() }, 600)
        refresh()
    }

    private fun stopWait() {
        Prefs.setWaitActive(this, false)
        stopService(Intent(this, WatchService::class.java))
        refresh()
    }

    private fun askBatteryWhitelist() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    // ---------- настройки ----------

    private fun showSettings() {
        val items = arrayOf(
            "Поправка на запуск роутера: ${Prefs.routerDelaySec(this)} с",
            "Зарядка как сигнал света: ${if (Prefs.chargerSignal(this)) "вкл" else "выкл"}",
            "Проверить будильник",
            "Очистить журнал"
        )
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> editDelay()
                    1 -> {
                        val v = !Prefs.chargerSignal(this)
                        Prefs.setChargerSignal(this, v)
                        toast(
                            if (v) "Включено: подключение зарядки = свет дали (работает, если телефон был отключён от зарядки в начале ожидания)"
                            else "Выключено"
                        )
                    }
                    2 -> {
                        Alarm.start(this)
                        startActivity(Intent(this, AlarmActivity::class.java).putExtra("test", true))
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Очистить журнал?")
                        .setMessage("Все записи и накопленная статистика будут удалены.")
                        .setPositiveButton("Очистить") { _, _ ->
                            Store.clear(this)
                            refresh()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .show()
    }

    private fun editDelay() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.routerDelaySec(this@MainActivity).toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Поправка на запуск роутера, сек")
            .setMessage(
                "Свет дали раньше, чем телефон нашёл Wi‑Fi: роутеру нужно время на загрузку. " +
                    "Эта поправка вычитается из времени подключения при записи в журнал."
            )
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v != null && v in 0..1800) {
                    Prefs.setRouterDelaySec(this, v)
                } else {
                    toast("Нужно число от 0 до 1800")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- вспомогательное ----------

    private fun bigButton(text: String, color: Int) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
    }

    private fun smallButton(text: String, color: Int) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- список журнала ----------

    private inner class LogAdapter : BaseAdapter() {
        var items: List<Event> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = items[position]
            val box = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(TextView(this@MainActivity).apply { textSize = 16f })
                addView(TextView(this@MainActivity).apply {
                    textSize = 12f
                    setTextColor(grey)
                })
            }
            val t1 = box.getChildAt(0) as TextView
            val t2 = box.getChildAt(1) as TextView

            t1.text = (if (e.on) "● Свет есть   " else "● Света нет   ") + Fmt.stamp(e.ts)
            t1.setTextColor(if (e.on) greenText else redText)

            // items отсортированы от новых к старым: «следующее» событие — на позицию выше.
            val next = if (position > 0) items[position - 1].ts else null
            val minutes = ((next ?: System.currentTimeMillis()) - e.ts) / 60_000
            t2.text = buildString {
                if (next == null) {
                    append(if (e.on) "свет есть уже " else "света нет уже ")
                } else {
                    append(if (e.on) "свет был " else "света не было ")
                }
                append(Fmt.dur(minutes))
                if (e.auto) append(" · авто")
                if (e.note.isNotEmpty()) append("\n").append(e.note)
            }
            return box
        }
    }
}
