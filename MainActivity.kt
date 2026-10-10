
package com.example.mytunnel

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), TunnelService.Listener {

    private lateinit var connectButton: TextView
    private lateinit var statusDot: View
    private lateinit var diagnosticsText: TextView
    private lateinit var logoImage: ImageView
    private lateinit var serverPill: LinearLayout
    private lateinit var countryLabel: TextView
    private lateinit var speedGraph: SpeedGraphView

    private var service: TunnelService? = null
    private var bound = false
    private var pendingConnect = false

    private var currentStatus =
        TunnelService.Status.DISCONNECTED

    private val colorRed = 0xFFE24B4A.toInt()
    private val colorGreen = 0xFF8BC98B.toInt()
    private val colorAmber = 0xFFF2A93B.toInt()
    private val colorPillRed = 0xFF8A5C5C.toInt()
    private val colorPillGreen = 0xFF5C8A5C.toInt()

    private var currentThemeColor = colorRed
    private var currentPillColor = colorPillRed

    private lateinit var pillDrawable: GradientDrawable

    private var pulseAnimator: ObjectAnimator? = null

    private val speedHandler = Handler(Looper.getMainLooper())
    private var lastRxBytes = 0L
    private var lastSampleTime = 0L

    private val speedRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            sampleSpeed()
            speedHandler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            val localBinder = binder as? TunnelService.LocalBinder

            if (localBinder == null) {
                pendingConnect = false
                diagnosticsText.text = "خطا: اتصال به سرویس برقرار نشد."
                return
            }

            service = localBinder.getService()
            bound = true

            if (pendingConnect) {
                pendingConnect = false
                try {
                    service?.start()
                } catch (e: Exception) {
                    diagnosticsText.text =
                        "خطا در اتصال: ${e.javaClass.simpleName}: ${e.message ?: "نامشخص"}"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)
            pillDrawable = GradientDrawable().apply {
             shape = GradientDrawable.RECTANGLE
             cornerRadius = 24f * resources.displayMetrics.density
            }
            connectButton = findViewById(R.id.connectButton)
            statusDot = findViewById(R.id.statusDot)
            diagnosticsText = findViewById(R.id.diagnosticsText)
            logoImage = findViewById(R.id.logoImage)
            serverPill = findViewById(R.id.serverPill)
            countryLabel = findViewById(R.id.countryLabel)
            speedGraph = findViewById(R.id.speedGraph)

            serverPill.background = pillDrawable
            applyTheme(colorRed, colorPillRed)

            TunnelService.listener = this

            connectButton.setOnClickListener {
                when (currentStatus) {
                    TunnelService.Status.DISCONNECTED -> {
                        if (service != null) {
                            try {
                                service?.start()
                            } catch (e: Exception) {
                                diagnosticsText.text =
                                    "خطا: ${e.javaClass.simpleName}: ${e.message ?: "نامشخص"}"
                            }
                        } else {
                            pendingConnect = true
                            startTunnelServiceSafely()
                        }
                    }

                    TunnelService.Status.CONNECTED,
                    TunnelService.Status.CONNECTING -> {
                        try {
                            service?.stop()
                        } catch (e: Exception) {
                            diagnosticsText.text =
                                "خطا هنگام قطع اتصال: ${e.message ?: "نامشخص"}"
                        }
                    }
                }
            }

            serverPill.setOnClickListener {
                showCountryPicker()
            }

            lastRxBytes = TrafficStats.getTotalRxBytes()
            lastSampleTime = System.currentTimeMillis()
            speedHandler.post(speedRunnable)

        } catch (e: Exception) {
            android.util.Log.e(
                "MyTunnelApp",
                "MainActivity initialization failed",
                e
            )
            throw e
        }
    }

    private fun startTunnelServiceSafely() {
        try {
            val intent = Intent(this, TunnelService::class.java)

            ContextCompat.startForegroundService(this, intent)

            if (!bound) {
                val bindStarted = bindService(
                    intent,
                    connection,
                    BIND_AUTO_CREATE
                )

                if (!bindStarted) {
                    pendingConnect = false
                    diagnosticsText.text =
                        "خطا: اتصال به سرویس برقرار نشد."
                }
            }
        } catch (e: Exception) {
            pendingConnect = false

            android.util.Log.e(
                "MyTunnelApp",
                "Could not start VPN service",
                e
            )

            diagnosticsText.text =
                "خطای سرویس: ${e.javaClass.simpleName}: ${e.message ?: "نامشخص"}"
        }
    }

    override fun onDestroy() {
        speedHandler.removeCallbacks(speedRunnable)
        pulseAnimator?.cancel()

        if (bound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                android.util.Log.e(
                    "MyTunnelApp",
                    "Unbind failed",
                    e
                )
            }
            bound = false
        }

        if (TunnelService.listener === this) {
            TunnelService.listener = null
        }

        super.onDestroy()
    }

    private fun showCountryPicker() {
        val names = resources.getStringArray(R.array.region_names)
        val codes = resources.getStringArray(R.array.region_codes)

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_server)
            .setItems(names) { _, which ->
                if (which !in codes.indices) return@setItems

                TunnelService.selectedRegion = codes[which]
                countryLabel.text = names[which]

                if (currentStatus != TunnelService.Status.DISCONNECTED) {
                    try {
                        service?.stop()
                        service?.start()
                    } catch (e: Exception) {
                        diagnosticsText.text =
                            "خطا در تغییر سرور: ${e.message ?: "نامشخص"}"
                    }
                }
            }
            .show()
    }

    private fun sampleSpeed() {
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()

        if (lastRxBytes > 0 && rx >= lastRxBytes) {
            val deltaBytes = rx - lastRxBytes
            val elapsedMs =
                (now - lastSampleTime).coerceAtLeast(1)

            val kbPerSecond =
                (deltaBytes / 1024f) / (elapsedMs / 1000f)

            speedGraph.addSample(kbPerSecond)
        }

        lastRxBytes = rx
        lastSampleTime = now
    }

    override fun onStatusChanged(status: TunnelService.Status) {
        runOnUiThread {
            currentStatus = status

            when (status) {
                TunnelService.Status.DISCONNECTED -> {
                    connectButton.text = getString(R.string.connect)
                    statusDot.setBackgroundResource(
                        R.drawable.status_dot_disconnected
                    )
                    stopPulse()
                    animateTheme(colorRed, colorPillRed)
                }

                TunnelService.Status.CONNECTING -> {
                    connectButton.text = getString(R.string.disconnect)
                    statusDot.setBackgroundResource(
                        R.drawable.status_dot_connecting
                    )
                    animateTheme(colorAmber, colorPillRed)
                    startPulse()
                }

                TunnelService.Status.CONNECTED -> {
                    connectButton.text = getString(R.string.disconnect)
                    statusDot.setBackgroundResource(
                        R.drawable.status_dot_connected
                    )
                    stopPulse()
                    animateTheme(colorGreen, colorPillGreen)
                }
            }
        }
    }

    override fun onDiagnostic(message: String) {
        runOnUiThread {
            diagnosticsText.text = message
        }
    }

    private fun animateTheme(
        toTextColor: Int,
        toPill: Int
    ) {
        val fromText = currentThemeColor
        val fromPill = currentPillColor

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400

            addUpdateListener { animator ->
                val fraction = animator.animatedFraction

                val textColor = ArgbEvaluator().evaluate(
                    fraction,
                    fromText,
                    toTextColor
                ) as Int

                val pillColor = ArgbEvaluator().evaluate(
                    fraction,
                    fromPill,
                    toPill
                ) as Int

                applyTheme(textColor, pillColor)
            }

            start()
        }

        currentThemeColor = toTextColor
        currentPillColor = toPill
    }

    private fun applyTheme(
        textColor: Int,
        pillColor: Int
    ) {
        connectButton.setTextColor(textColor)
        logoImage.setColorFilter(
            textColor,
            PorterDuff.Mode.SRC_IN
        )
        pillDrawable.setColor(pillColor)
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return

        pulseAnimator = ObjectAnimator.ofFloat(
            logoImage,
            View.SCALE_X,
            1f,
            1.08f,
            1f
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(
            logoImage,
            View.SCALE_Y,
            1f,
            1.08f,
            1f
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null

        logoImage.scaleX = 1f
        logoImage.scaleY = 1f
    }
}
