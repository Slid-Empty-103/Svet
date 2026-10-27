package com.example.svet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Режим «Ждать свет».
 *
 * Роутер без света выключен, поэтому Wi-Fi пропадает. Как только свет дали, роутер загружается,
 * телефон подключается к Wi-Fi — и мы проверяем, что в сети РЕАЛЬНО есть интернет
 * (грузим страницу Википедии «Земля» именно через эту Wi-Fi сеть). Если да — звоним и пишем в журнал.
 *
 * Время «свет дали» в журнале = момент подключения к Wi-Fi минус поправка на загрузку роутера.
 */
class WatchService : Service() {

    companion object {
        const val CH_WATCH = "watch"
        const val CH_ALARM = "alarm"
        const val ID_WATCH = 1
        const val ID_ALARM = 2
        const val PROBE_URL = "https://ru.wikipedia.org/wiki/%D0%97%D0%B5%D0%BC%D0%BB%D1%8F"
    }

    private lateinit var cm: ConnectivityManager
    private lateinit var nm: NotificationManager
    private val main = Handler(Looper.getMainLooper())

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var monitoring = false
    @Volatile private var fired = false
    @Volatile private var sawDown = true
    @Volatile private var chargerArmed = false

    private val wifiUp = ConcurrentHashMap<Network, Long>()
    private val probes = ConcurrentHashMap<Network, Thread>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Ожидание света", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Свет дали", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID_WATCH, watchNotification("Жду, когда дадут свет…"))

        if (monitoring) return START_STICKY

        if (intent == null && !Prefs.waitActive(this)) {
            // Системный перезапуск, а ожидание уже закончено.
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent != null) {
            // Свежий запуск: если телефон сейчас на Wi-Fi, ждём, пока он пропадёт и вернётся.
            Prefs.beginWait(this, sawDown = !wifiConnected(), chargerArmed = !isCharging())
        }
        sawDown = Prefs.sawDown(this)
        chargerArmed = Prefs.chargerArmed(this)
        fired = false
        startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        Alarm.stop()
        nm.cancel(ID_ALARM)
        super.onDestroy()
    }

    // ---------- наблюдение ----------

    private fun startMonitoring() {
        monitoring = true

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifiAvailable(network)
            }

            override fun onLost(network: Network) {
                wifiUp.remove(network)
                probes.remove(network)?.interrupt()
                sawDown = true
                Prefs.setSawDown(this@WatchService, true)
            }
        }
        callback = cb
        cm.registerNetworkCallback(req, cb)

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        chargerArmed = true
                        Prefs.setChargerArmed(context, true)
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        if (chargerArmed && Prefs.chargerSignal(context)) {
                            onPowerBack(System.currentTimeMillis(), "зарядка подключена")
                        }
                    }
                }
            }
        }
        receiver = r
        registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    }

    private fun stopMonitoring() {
        monitoring = false
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
        wifiUp.clear()
        probes.values.forEach { it.interrupt() }
        probes.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun onWifiAvailable(network: Network) {
        val at = System.currentTimeMillis()
        wifiUp[network] = at
        if (!sawDown) return // Wi-Fi был и до начала ожидания — это не «свет дали»
        startProbe(network, at)
    }

    private fun startProbe(network: Network, wifiAt: Long) {
        if (probes.containsKey(network)) return
        acquireWake()
        val t = Thread {
            var ok = false
            while (monitoring && !fired && wifiUp.containsKey(network)) {
                if (probe(network)) {
                    ok = true
                    break
                }
                try {
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            probes.remove(network)
            if (ok) {
                val internetAt = System.currentTimeMillis()
                val delay = Prefs.routerDelaySec(this)
                val lastOff = Store.load(this).lastOrNull { !it.on }?.ts ?: 0L
                // Свет дали раньше, чем телефон подключился: отнимаем время загрузки роутера,
                // но не раньше момента, когда отметили «света нет».
                val est = min(wifiAt, max(wifiAt - delay * 1000L, lastOff + 1000L))
                val note = "Wi‑Fi в ${Fmt.clockSec(wifiAt)}, интернет в ${Fmt.clockSec(internetAt)}, " +
                    "поправка −$delay с"
                onPowerBack(est, note)
            }
        }
        t.isDaemon = true
        probes[network] = t
        t.start()
    }

    /** Проверяем, что через эту Wi-Fi сеть реально открывается Википедия (а не заглушка роутера/провайдера). */
    private fun probe(network: Network): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SvetApp/1.0 (power outage monitor)")
            if (conn.responseCode != 200) {
                false
            } else {
                val buf = ByteArray(16 * 1024)
                var len = 0
                conn.inputStream.use { s ->
                    while (len < buf.size) {
                        val n = s.read(buf, len, buf.size - len)
                        if (n <= 0) break
                        len += n
                    }
                }
                val head = String(buf, 0, len, Charsets.UTF_8)
                head.contains("ikipedia") || head.contains("Википеди")
            }
        } catch (_: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- свет дали ----------

    @Synchronized
    private fun onPowerBack(ts: Long, note: String) {
        if (fired || !monitoring) return
        fired = true

        Store.add(this, ts, true, auto = true, note = note)
        Prefs.setWaitActive(this, false)

        main.post {
            stopMonitoring()
            Alarm.start(this)

            val full = PendingIntent.getActivity(
                this, 1,
                Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle("⚡ Свет дали!")
                .setContentText("Нажми, чтобы выключить сигнал")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setOngoing(true)
                .build()
            nm.notify(ID_ALARM, n)
            nm.notify(ID_WATCH, watchNotification("⚡ Свет дали!"))

            try {
                startActivity(
                    Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }

            // Сервис живёт, пока сигнал не выключат (или максимум 10 минут).
            main.postDelayed({ stopSelf() }, 10 * 60 * 1000L)
        }
    }

    // ---------- вспомогательное ----------

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "svet:probe").apply {
            acquire(10 * 60 * 1000L)
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiConnected(): Boolean = cm.allNetworks.any {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun isCharging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun watchNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("Свет")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
