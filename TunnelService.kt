package com.example.mytunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject

class TunnelService : Service(), PsiphonTunnel.HostService {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onStatusChanged(status: Status)
        fun onDiagnostic(message: String)
    }

    companion object {
        const val CHANNEL_ID = "tunnel_service_channel"
        const val NOTIFICATION_ID = 1
        var listener: Listener? = null
        // two-letter country code to connect through, e.g. "DE"; empty = automatic
        var selectedRegion: String = ""
    }

    private lateinit var psiphonTunnel: PsiphonTunnel
    private var status = Status.DISCONNECTED

    inner class LocalBinder : android.os.Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        psiphonTunnel = PsiphonTunnel.newPsiphonTunnel(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun start() {
        if (status != Status.DISCONNECTED) return
        setStatus(Status.CONNECTING)
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
        Thread {
            try {
                psiphonTunnel.startTunneling(getPsiphonConfig())
            } catch (e: Exception) {
                listener?.onDiagnostic("Error starting tunnel: ${e.message}")
                setStatus(Status.DISCONNECTED)
            }
        }.start()
    }

    fun stop() {
        Thread {
            psiphonTunnel.stop()
            setStatus(Status.DISCONNECTED)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.start()
    }

    private fun setStatus(newStatus: Status) {
        status = newStatus
        listener?.onStatusChanged(newStatus)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Tunnel Status", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

    override fun getContext() = applicationContext

    override fun getPsiphonConfig(): String {
        val config = assets.open("psiphon_config.json").bufferedReader().use { it.readText() }
        val json = JSONObject(config)
        json.put("LocalSocksProxyPort", 1080)
        json.put("LocalHttpProxyPort", 8080)
        if (selectedRegion.isNotEmpty()) {
            json.put("EgressRegion", selectedRegion)
        }
        return json.toString()
    }

    override fun onDiagnosticMessage(message: String) {
        listener?.onDiagnostic(message)
    }

    override fun onConnecting() {
        setStatus(Status.CONNECTING)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Connecting…"))
    }

    override fun onConnected() {
        setStatus(Status.CONNECTED)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Connected"))
    }

    override fun onExiting() {
        setStatus(Status.DISCONNECTED)
    }

    // Add overrides for any other HostService methods your AAR version
    // requires (onAvailableEgressRegions, onSocksProxyPortInUse, etc.) —
    // Android Studio's Alt+Enter "Implement members" will list them.
}
