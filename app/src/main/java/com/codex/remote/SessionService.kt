package com.codex.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.codex.remote.data.diagnostics.DebugApi

object HostSessions : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

class SessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("remote-sessions", "Codex", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, SessionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1001, NotificationCompat.Builder(this, "remote-sessions")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Codex")
            .setContentText(getString(R.string.remote_connections_active))
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), stop).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) {
        // ponytail: Android 15 limits background dataSync service time. Preserve disk
        // history and reconnect when the app reopens; do not bypass the OS limit.
        DebugApi.record("android_sync_service_timeout")
        stopSelf()
    }
    override fun onDestroy() {
        HostSessions.viewModelStore.clear()
        super.onDestroy()
    }
}
