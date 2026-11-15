package ru.mscexb.screenkey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Служба защиты экрана. Три режима:
 *
 * MODE_KEY      — «наушники = ключ»: шторка, ПОКА наушники НЕ подключены;
 * MODE_INVERTED — «наушники = блюр»: шторка, ПОКА наушники подключены;
 * MODE_FRONTAL  — «фронтальный замок»: шторка, пока фронтальная камера не видит
 *                 ровно одно лицо строго напротив экрана (и, по желанию,
 *                 пока не подключены наушники).
 *
 * Все пороги и тексты шторки редактируются пользователем в SettingsActivity
 * и применяются на лету. ACTION_TEST показывает шторку на 3 секунды для проверки.
 */
class KeyService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences
    private var overlay: View? = null

    // Фронтальный замок
    @Volatile
    private var faceOk = false
    private var cameraRunning = false
    private var analyzer: FaceAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Тест шторки
    private var testUntil = 0L

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        startForeground(NOTIFY_ID, buildNotification())
        syncCamera()
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            testUntil = SystemClock.elapsedRealtime() + TEST_DURATION_MS
            mainHandler.postDelayed({ refresh() }, TEST_DURATION_MS + 50)
        }
        syncCamera()
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopFaceGuard()
        cameraExecutor.shutdown()
        removeOverlay()
        super.onDestroy()
    }

    // ---------- Условия «открыто/закрыто» ----------

    private fun acceptedTypes() = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET
    )

    private fun earbudsConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in acceptedTypes() }

    private fun shouldCover(): Boolean {
        if (SystemClock.elapsedRealtime() < testUntil) return true
        return when (prefs.getInt(KEY_MODE, MODE_KEY)) {
            MODE_INVERTED -> earbudsConnected()
            MODE_FRONTAL -> {
                val needBuds = prefs.getBoolean(KEY_FRONTAL_NEED_EARBUDS, true)
                val ok = faceOk && (!needBuds || earbudsConnected())
                !ok
            }
            else -> !earbudsConnected()
        }
    }

    // ---------- Фронтальный замок (камера) ----------

    private fun syncCamera() {
        if (prefs.getInt(KEY_MODE, MODE_KEY) == MODE_FRONTAL && !cameraRunning) startFaceGuard()
        else if (prefs.getInt(KEY_MODE, MODE_KEY) != MODE_FRONTAL && cameraRunning) stopFaceGuard()
    }

    private fun startFaceGuard() {
        if (cameraRunning) return
        cameraRunning = true
        faceOk = false // безопасное умолчание: пока лицо не подтверждено — шторка закрыта
        val owner = ServiceLifecycleOwner()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val a = FaceAnalyzer(prefs) { ok -> mainHandler.post { faceOk = ok; refresh() } }
                analysis.setAnalyzer(cameraExecutor, a)
                analyzer = a
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                cameraProvider = provider
                lifecycleOwner = owner
            } catch (_: Exception) {
                // Нет разрешения CAMERA или нет камеры: шторка остаётся закрытой.
                faceOk = false
            }
        }, Executor { mainHandler.post(it) })
    }

    private fun stopFaceGuard() {
        if (!cameraRunning) return
        cameraRunning = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        lifecycleOwner?.let { it.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
        lifecycleOwner = null
        analyzer?.close()
        analyzer = null
        faceOk = false
    }

    private class ServiceLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override fun getLifecycle(): Lifecycle = registry
    }

    // ---------- Шторка ----------

    private fun refresh() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val current = overlay
        when {
            shouldCover() && current == null -> {
                val v = LayoutInflater.from(this).inflate(R.layout.overlay_lock, null)
                v.findViewById<TextView>(R.id.overlay_title).text = customText(KEY_OV_TITLE, R.string.overlay_title)
                v.findViewById<TextView>(R.id.overlay_hint).text = hintText()
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                )
                try {
                    wm.addView(v, params)
                    overlay = v
                } catch (_: Exception) {
                }
            }
            !shouldCover() && current != null -> removeOverlay()
        }
    }

    private fun customText(key: String, defRes: Int): String {
        val custom = prefs.getString(key, "")?.trim().orEmpty()
        return if (custom.isNotEmpty()) custom else getString(defRes)
    }

    private fun hintText(): String = when (prefs.getInt(KEY_MODE, MODE_KEY)) {
        MODE_INVERTED -> customText(KEY_OV_HINT_INV, R.string.hint_inverted)
        MODE_FRONTAL -> {
            val custom = prefs.getString(KEY_OV_HINT_FRONTAL, "")?.trim().orEmpty()
            if (custom.isNotEmpty()) custom
            else getString(R.string.hint_frontal) +
                if (prefs.getBoolean(KEY_FRONTAL_NEED_EARBUDS, true))
                    "\n" + getString(R.string.hint_frontal_buds) else ""
        }
        else -> customText(KEY_OV_HINT_KEY, R.string.hint_key)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    // ---------- Уведомление ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(if (shouldCover()) getString(R.string.notify_covered) else getString(R.string.notify_open))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PREFS = "screenkey"
        const val KEY_MODE = "mode"
        const val KEY_FRONTAL_NEED_EARBUDS = "frontal_need_earbuds"
        const val KEY_AUTOSTART = "autostart"

        // Пороги фронтального замка (редактируются пользователем)
        const val KEY_YAW = "yaw_max"
        const val KEY_ROLL = "roll_max"
        const val KEY_CENTER = "center_percent"
        const val KEY_PASS = "pass_frames"
        const val KEY_FAIL = "fail_frames"
        const val DEFAULT_YAW = 18
        const val DEFAULT_ROLL = 25
        const val DEFAULT_CENTER = 45
        const val DEFAULT_PASS = 3
        const val DEFAULT_FAIL = 4

        // Тексты шторки (редактируются пользователем, пусто = стандартные)
        const val KEY_OV_TITLE = "ov_title"
        const val KEY_OV_HINT_KEY = "ov_hint_key"
        const val KEY_OV_HINT_INV = "ov_hint_inverted"
        const val KEY_OV_HINT_FRONTAL = "ov_hint_frontal"

        const val MODE_KEY = 0
        const val MODE_INVERTED = 1
        const val MODE_FRONTAL = 2

        const val ACTION_TEST = "ru.mscexb.screenkey.TEST"
        const val TEST_DURATION_MS = 3000L

        private const val CHANNEL = "screen_key"
        private const val NOTIFY_ID = 1

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, KeyService::class.java)
            action?.let { i.action = it }
            context.startForegroundService(i)
        }
    }
}
