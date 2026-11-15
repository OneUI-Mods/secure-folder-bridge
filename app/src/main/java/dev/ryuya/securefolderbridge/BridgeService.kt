package dev.ryuya.securefolderbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import java.util.concurrent.Executors

class BridgeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var process: java.lang.Process? = null
    private var starting = false
    @Volatile private var stopRequested = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            disconnect()
            return START_NOT_STICKY
        }
        if (starting) return START_NOT_STICKY
        starting = true
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("bridge", "Folder connection", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "bridge")
            .setSmallIcon(R.drawable.ic_bridge).setContentTitle("Secure Folder Bridge")
            .setContentText("/storage/emulated/0/SecureFolder").setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build())
        update("CONNECTING")
        val readonly = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("readonly", false)
        worker.execute {
            try {
                if (stopRequested) return@execute
                val child = RootShell.start(this, if (readonly) "readonly" else "readwrite")
                process = child
                if (stopRequested) disconnect()
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line == "CONNECTED" -> update("CONNECTED")
                            line == "LOCKED" -> update("LOCKED")
                            line.startsWith("ERROR:") -> update(line)
                        }
                    }
                }
                child.waitFor()
            } catch (error: Throwable) {
                update("ERROR: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                process = null
                val current = getSharedPreferences("settings", MODE_PRIVATE).getString("state", "")
                if (current == "CONNECTED" || current == "CONNECTING") update("OFF")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun update(state: String) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("state", state).apply()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun disconnect() {
        stopRequested = true
        val current = process
        if (current == null) {
            if (!starting) stopSelf()
            return
        }
        try {
            current.outputStream.write("STOP\n".toByteArray())
            current.outputStream.flush()
            current.outputStream.close()
        } catch (_: Exception) {
            runCatching { current.outputStream.close() }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        disconnect()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATE = "dev.ryuya.securefolderbridge.STATE"
    }
}
