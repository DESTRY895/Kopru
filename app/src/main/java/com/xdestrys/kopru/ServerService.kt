package com.xdestrys.kopru

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/** Ekran kapanınca da sunucu çalışsın diye ön plan servisi. */
class ServerService : Service() {
    private var server: HttpServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.init(this)
        val ip = Hub.localIp()
        val address = ip?.let { "$it:${Hub.PORT}" }
        startForeground(1, notification(address), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (server == null) {
            server = HttpServer(this, Hub.PORT).also { it.start() }
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kopru:server").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kopru:wifi").apply { acquire() }
        }
        Hub.setAddress(address ?: "")
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        Hub.setAddress(null)
        super.onDestroy()
    }

    private fun notification(address: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Köprü sunucusu", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Köprü açık")
            .setContentText(address?.let { "Tarayıcıda: http://$it" } ?: "Wi‑Fi bağlantısı yok")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Kapat", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "server"
        const val ACTION_STOP = "stop"

        fun start(c: Context) = c.startForegroundService(Intent(c, ServerService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, ServerService::class.java).setAction(ACTION_STOP))
    }
}
