package com.mobileagent.demo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandJob: Job? = null
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, buildNotification("准备启动 Cloudflare Tunnel", null))
        val action = intent?.action ?: actionStart
        commandJob?.cancel()
        commandJob = serviceScope.launch {
            when (action) {
                actionStop -> {
                    monitorJob?.cancel()
                    monitorJob = null
                    val status = TunnelRuntime.stopManagedProcess(applicationContext, lastErrorOverride = null)
                    updateNotification(status)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                else -> {
                    val status = TunnelRuntime.startManagedProcess(applicationContext) { snapshot ->
                        updateNotification(snapshot)
                    }
                    updateNotification(status)
                    ensureMonitorLoop()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        commandJob?.cancel()
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(status: TunnelStatusSnapshot) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(status.statusLabel, status.lastError))
    }

    private fun buildNotification(statusLabel: String, lastError: String?): android.app.Notification {
        val contentText = when {
            !lastError.isNullOrBlank() -> lastError
            else -> "当前状态：$statusLabel"
        }
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("移动代理 Tunnel")
            .setContentText(contentText.take(96))
            .setSmallIcon(R.drawable.ic_agent_badge)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "移动代理 Tunnel",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 cloudflared 前台服务存活，并显示真实运行状态。"
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureMonitorLoop() {
        if (monitorJob?.isActive == true) {
            return
        }
        monitorJob = serviceScope.launch {
            var unhealthyPolls = 0
            while (isActive) {
                delay(monitorIntervalMs)
                val status = TunnelRuntime.refreshStatus(applicationContext, lastErrorOverride = null)
                updateNotification(status)

                val healthy = status.tokenBound && status.running && status.statusLabel == "已连接"
                if (healthy || !status.tokenBound) {
                    unhealthyPolls = 0
                    continue
                }

                unhealthyPolls += 1
                if (unhealthyPolls < unhealthyThreshold) {
                    continue
                }

                val recovered = TunnelRuntime.startManagedProcess(applicationContext) { snapshot ->
                    updateNotification(snapshot)
                }
                updateNotification(recovered)
                unhealthyPolls = if (recovered.running && recovered.statusLabel == "已连接") 0 else unhealthyThreshold - 1
            }
        }
    }

    companion object {
        private const val actionStart = "com.mobileagent.demo.action.START_TUNNEL"
        private const val actionStop = "com.mobileagent.demo.action.STOP_TUNNEL"
        private const val channelId = "mobile-agent-tunnel"
        private const val notificationId = 1107
        private const val monitorIntervalMs = 15_000L
        private const val unhealthyThreshold = 2

        fun requestStart(context: Context) {
            val intent = Intent(context, TunnelForegroundService::class.java).setAction(actionStart)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestStop(context: Context) {
            val intent = Intent(context, TunnelForegroundService::class.java).setAction(actionStop)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
