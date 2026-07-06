package dev.allan.workoutapp.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.allan.workoutapp.MainActivity
import dev.allan.workoutapp.R

/**
 * Foreground service that keeps the process alive during a workout session
 * (HyperOS kills background apps aggressively), shows the session/rest/set
 * timers as a live (chronometer) notification, and fires the countdown-end
 * alert (vibration + beep) even with the screen off or the app minimized.
 *
 * Countdowns render natively via setChronometerCountDown — the OS ticks the
 * notification, no per-second updates needed from us.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var alertRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundWithNotification()
            ACTION_SHOW_COUNTDOWN -> {
                val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.rest)
                notify(buildNotification(title = label, chronometerBase = endAt, countDown = true))
                scheduleAlert(endAt)
            }
            ACTION_SHOW_DEFAULT -> {
                cancelAlert()
                notify(defaultNotification())
            }
            ACTION_STOP -> {
                cancelAlert()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureChannel(): NotificationManager {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return manager
    }

    private fun buildNotification(title: String, chronometerBase: Long, countDown: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(tapIntent)
            .setUsesChronometer(true)
            .setChronometerCountDown(countDown)
            .setWhen(chronometerBase)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun defaultNotification(): Notification = buildNotification(
        title = getString(R.string.session_running),
        chronometerBase = SessionManager.state.value.sessionStartedAt ?: System.currentTimeMillis(),
        countDown = false,
    )

    private fun notify(notification: Notification) {
        ensureChannel().notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithNotification() {
        ensureChannel()
        val notification = defaultNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun scheduleAlert(endAt: Long) {
        cancelAlert()
        val delay = endAt - System.currentTimeMillis()
        if (delay <= 0) return
        val r = Runnable {
            fireAlert()
            // Countdown over — swap the notification back to session elapsed time.
            notify(defaultNotification())
        }
        alertRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun cancelAlert() {
        alertRunnable?.let(handler::removeCallbacks)
        alertRunnable = null
    }

    private fun fireAlert() {
        runCatching {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
            )
        }
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                .startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        }
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SHOW_COUNTDOWN = "show_countdown"
        const val ACTION_SHOW_DEFAULT = "show_default"
        const val EXTRA_END_AT = "end_at"
        const val EXTRA_LABEL = "label"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TimerService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TimerService::class.java).setAction(ACTION_STOP))
        }

        /** Show a live countdown in the notification and alert when it ends. */
        fun showCountdown(context: Context, endAt: Long, label: String) {
            context.startService(
                Intent(context, TimerService::class.java)
                    .setAction(ACTION_SHOW_COUNTDOWN)
                    .putExtra(EXTRA_END_AT, endAt)
                    .putExtra(EXTRA_LABEL, label)
            )
        }

        /** Revert to the session-elapsed chronometer and cancel any pending alert. */
        fun showDefault(context: Context) {
            context.startService(
                Intent(context, TimerService::class.java).setAction(ACTION_SHOW_DEFAULT)
            )
        }
    }
}
