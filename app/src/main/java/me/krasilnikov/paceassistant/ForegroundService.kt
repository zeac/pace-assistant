package me.krasilnikov.paceassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ForegroundService : Service() {

    private val stopIntent by lazy {
        PendingIntent.getService(
            this,
            0,
            Intent(this, ForegroundService::class.java).apply {
                action = "stop"
            },
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private val clickIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(workoutChannelId, "Workout", NotificationManager.IMPORTANCE_LOW)

            with(NotificationManagerCompat.from(PaceApplication.instance)) {
                createNotificationChannel(channel)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            Worker.stop(false)

            stopSelf(startId)

            return START_NOT_STICKY
        }

        val stopAction = NotificationCompat.Action.Builder(0, "Stop", stopIntent).build()

        startForeground(
            1,
            with(NotificationCompat.Builder(this, workoutChannelId)) {
                setLocalOnly(true)
                setAutoCancel(false)
                setContentIntent(clickIntent)
                addAction(stopAction)
                setAllowSystemGeneratedContextualActions(false)
                setSilent(true)
                setOngoing(true)
                setContentTitle("Title")
                setSmallIcon(R.drawable.ic_launcher_foreground)
                setUsesChronometer(true)
                setVisibility(NotificationCompat.VISIBILITY_SECRET)
                build()
            },
        )

        return START_STICKY
    }

    companion object {

        const val workoutChannelId = "workout"
    }
}
