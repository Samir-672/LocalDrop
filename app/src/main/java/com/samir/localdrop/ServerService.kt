package com.samir.localdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.samir.localdrop.data.SharedRepository
import com.samir.localdrop.server.LocalDropServer

class ServerService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        startForeground(1001, notification())
        return START_STICKY
    }

    private fun startServer() {
        if (server?.isAlive == true) return
        repository = SharedRepository(applicationContext)
        server = findAvailableServer(repository!!)
        server?.start()
        activeAddress = LocalDropServer.findWifiAddress()
    }

    private fun findAvailableServer(repo: SharedRepository): LocalDropServer {
        for (port in 8080..8089) {
            val candidate = LocalDropServer(applicationContext, repo, port)
            try {
                candidate.start()
                candidate.stop()
            } catch (_: Exception) {
                continue
            }
            val actual = LocalDropServer(applicationContext, repo, port)
            activePort = port
            return actual
        }
        activePort = 8080
        return LocalDropServer(applicationContext, repo, 8080)
    }

    private fun stopServer() {
        server?.stop()
        server?.invalidateSessions()
        server = null
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ip = activeAddress
        val url = if (ip != null) "http://$ip:$activePort" else "Server running"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("LocalDrop server is running")
            .setContentText(url)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var server: LocalDropServer? = null
        var repository: SharedRepository? = null
        var activeAddress: String? = null
        var activePort: Int = 8080
        const val CHANNEL_ID = "localdrop_server"
        const val ACTION_STOP = "com.samir.localdrop.STOP"
    }
}
