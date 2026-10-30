package kr.carescape.multiverse.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.voice.Voice

/**
 * Keeps the Vehicle SDK listeners and the safety guard alive even when another app (the navi) is in front
 * or the system hides the game because the car started moving.
 */
class CarService : Service() {
    private var pleos: PleosVehicle? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "방탈출 안전 감시", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("차량탈출 : AI 멀티버스")
            .setContentText("안전 샌드박스 감시 중")
            .setOngoing(true)
            .build()
        startForeground(1, n)
        Voice.init(applicationContext)
        GameEngine.attach(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val demo = intent?.getBooleanExtra(EXTRA_DEMO, false) ?: false
        if (demo) {
            pleos?.stop()
            pleos = null
            VehicleBus.controls = FakeVehicle
            FakeVehicle.reset()
        } else if (pleos == null) {
            pleos = PleosVehicle(applicationContext).also {
                VehicleBus.controls = it
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pleos?.stop()
        pleos = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "carescape_guard"
        private const val EXTRA_DEMO = "demo"

        fun start(context: Context, demo: Boolean) {
            val i = Intent(context, CarService::class.java).putExtra(EXTRA_DEMO, demo)
            context.startForegroundService(i)
        }
    }
}
