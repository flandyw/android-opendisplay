package app.opendisplay.receiver.cast

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import app.opendisplay.receiver.R
import app.opendisplay.receiver.ReceiverForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Owns the screen capture while this phone is shared to a receiver; runs in the background on purpose. */
class ScreenCastService : Service() {
    private var projection: MediaProjection? = null
    private var sender: ScreenCastSender? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val host = intent?.getStringExtra(EXTRA_HOST)
        val name = intent?.getStringExtra(EXTRA_NAME) ?: host
        if (intent?.action == ACTION_STOP || data == null || host == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        sender?.stop()
        projection?.stop()
        // Android 10+ requires the mediaProjection foreground service to be running before getMediaProjection.
        val notification = notification(name.orEmpty())
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED), data)
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (projection === proj) stopSelf() } // user revoked it from the system UI
        }, main)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val (w, h) = ScreenCastSender.streamSize(metrics.widthPixels, metrics.heightPixels)
        // shortcut: the stream size is fixed at start; rotating the phone letterboxes until the next share.
        lateinit var started: ScreenCastSender
        started = ScreenCastSender(host, intent.getIntExtra(EXTRA_PORT, 9000), proj, w, h, metrics.densityDpi) { error ->
            main.post {
                if (sender !== started) return@post // a newer share replaced this one
                if (error != null) lastError.value = error
                stopSelf()
            }
        }
        sender = started
        started.start()
        lastError.value = null
        target.value = name
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sender?.stop()
        sender = null
        projection?.stop()
        projection = null
        target.value = null
        super.onDestroy()
    }

    private fun notification(name: String) = NotificationCompat.Builder(this, ReceiverForegroundService.ensureChannel(this))
        .setContentTitle(getString(R.string.cast_notification_title, name))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setSilent(true)
        .addAction(0, getString(R.string.cast_stop), PendingIntent.getService(this, 1,
            Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "app.opendisplay.receiver.STOP_CAST"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NAME = "name"

        private val target = MutableStateFlow<String?>(null)
        private val lastError = MutableStateFlow<String?>(null)
        /** Name of the receiver this phone is being shared to, or null. */
        val sharingTo: StateFlow<String?> = target
        /** Why the last share ended on its own, or null. */
        val error: StateFlow<String?> = lastError

        /** Call with the result of [MediaProjectionManager.createScreenCaptureIntent], while in the foreground. */
        fun start(context: Context, resultCode: Int, data: Intent, host: String, port: Int, name: String) {
            val i = Intent(context, ScreenCastService::class.java)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_HOST, host).putExtra(EXTRA_PORT, port).putExtra(EXTRA_NAME, name)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCastService::class.java))
        }
    }
}
