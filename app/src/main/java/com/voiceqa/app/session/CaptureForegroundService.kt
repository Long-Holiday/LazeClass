package com.voiceqa.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voiceqa.app.MainActivity
import com.voiceqa.app.R
import com.voiceqa.app.VoiceQaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_qa_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.voiceqa.app.action.START_SERVICE"
        const val ACTION_STOP = "com.voiceqa.app.action.STOP_SERVICE"
        const val ACTION_FLUSH = "com.voiceqa.app.action.FLUSH_NOW"

        fun start(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? VoiceQaApplication
        val coordinator = app?.sessionCoordinator

        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    coordinator?.stopSession()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_FLUSH -> {
                serviceScope.launch {
                    coordinator?.flushNow()
                }
            }
            else -> {
                startForegroundWithNotification()
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("正在持续监听语音并实时问答...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CaptureForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val flushIntent = Intent(this, CaptureForegroundService::class.java).apply {
            action = ACTION_FLUSH
        }
        val flushPendingIntent = PendingIntent.getService(
            this, 2, flushIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceQA 正在监听")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(0, "立即分析", flushPendingIntent)
            .addAction(0, "停止监听", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音持续识别服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台持续监听麦克风并进行智能问答分析"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
