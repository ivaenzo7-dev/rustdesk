package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "RustDesk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }
                    if (authorized) {
                        if (!isFileTransfer && !isStart) {
                            startCapture()
                        }
                        // ШТОРА «ИДЁТ АРЕНДА» — здесь Rust-ядро авторизует пира
                        // напрямую (минуя Dart sendLoginResponse), поэтому вызов
                        // шторы должен быть тут, а не только в server_model.dart.
                        if (!isFileTransfer) {
                            Log.i("RentalBanner", "[MainService] rust authorized peer -> show curtain")
                            RentalBannerService.show(this@MainService)
                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(mediaProjection)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(mediaProjection)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture()
                // Сессия завершена rust-стороной — убираем штору.
                Log.i("RentalBanner", "[MainService] rust stop_capture -> hide curtain")
                RentalBannerService.hide(this@MainService)
                // Вытесняем историю буфера обмена Samsung Keyboard — заливаем
                // recent-list уникальными невидимыми клипами, реальные записи
                // предыдущей сессии уезжают за хвост и пропадают.
                ClipboardFlusher.flushAsync(this@MainService)
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
        @Volatile var instance: MainService? = null
        // Track whether a remote session is currently active
        var isSessionActive: Boolean = false
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    // ── WebSocket / JWT fields ──────────────────────────────────────────────
    private var wsClient: okhttp3.WebSocket? = null
    private var wsOkHttpClient: okhttp3.OkHttpClient? = null
    private val wsUrl = "wss://ws.mobirent.io/ws"
    private val authUrl = "https://ws.mobirent.io/auth"
    private var currentOtp: String = ""
    private var lastTempPasswordSent: String = ""
    private var jwtToken: String = ""
    private var isAuthenticating: Boolean = false
    private var wsReconnectAttempts = 0
    private var wsShouldReconnect = true
    private val wsReconnectMaxDelay = 30000L
    private val wsReconnectBaseDelay = 2000L
    private val wsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // ───────────────────────────────────────────────────────────────────────

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

        createForegroundNotification()

        // Start WebSocket authentication on service creation
        wsShouldReconnect = true
        wsReconnectAttempts = 0
        authenticateDevice()
    }

    override fun onDestroy() {
        checkMediaPermission()
        disconnectWebSocket()
        // Сервис уничтожается — убедимся, что штора не осталась висеть.
        Log.i("RentalBanner", "[MainService] onDestroy -> hide curtain")
        RentalBannerService.hide(this@MainService)
        // Доп. страховка: ещё раз вытеснить историю буфера — на случай если
        // сессия завершилась не через rust stop_capture, а аварийно.
        ClipboardFlusher.flushAsync(this@MainService)
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                FFI.startService()
            }
            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                checkMediaPermission()
                _isReady = true
            } ?: let {
                Log.d(logTag, "getParcelableExtra intent null, invoke requestMediaProjection")
                requestMediaProjection()
            }
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            // TODO
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    2  // буфер 4 — при быстрых переходах/свайпах не теряем кадры анимации
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        // Hot path — минимум аллокаций и блокировок
                        var image: android.media.Image? = null
                        try {
                            image = imageReader.acquireLatestImage()
                            if (image == null || !isStart) return@setOnImageAvailableListener
                            val buffer = image.planes[0].buffer
                            buffer.rewind()
                            FFI.onVideoFrameUpdate(buffer)
                        } catch (_: Exception) {
                        } finally {
                            image?.close() // освобождаем буфер немедленно
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(mediaProjection)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(mediaProjection)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        surface = createSurface()

        if (useVP9) {
            startVP9VideoRecorder(mediaProjection!!)
        } else {
            startRawVideoRecorder(mediaProjection!!)
        }

        // Audio capture intentionally disabled for the rental fleet build. The
        // RECORD_AUDIO permission has been stripped from the manifest, so any
        // attempt to construct AudioRecord would fail at the OS layer anyway.
        // Skip the call entirely to avoid log noise on every session start.
        Log.d(logTag, "audio recorder disabled (rental build)")
        checkMediaPermission()
        _isStart = true
        FFI.setFrameRawEnable("video",true)
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        return true
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()
        // ВАЖНО: занулить ссылку — иначе любой код, проверяющий surface != null,
        // использует уже освобождённый Surface (источник zombie VirtualDisplay
        // в прошлом). Парная защита к release() выше.
        surface = null

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        mediaProjection = null
        checkMediaPermission()
        instance = null
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection) {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return
        }
        createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection) {
        createMediaCodec()
        videoEncoder?.let {
            surface = it.createInputSurface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface!!.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            it.setCallback(cb)
            it.start()
            createOrSetVirtualDisplay(mp, surface!!)
        }
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface) {
        try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                // AUTO_MIRROR — зеркалирует физический экран. Штора скрывается
                // из захвата на уровне SurfaceControl#setSkipScreenshot, а не
                // через флаги VD (OWN_CONTENT_ONLY взаимоисключающ с AUTO_MIRROR
                // и overlay не исключает — проверено).
                virtualDisplay = mp.createVirtualDisplay(
                    "RustDeskVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, null, null
                )
            }
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            requestMediaProjection()
        }
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            // .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            // .addAction(R.drawable.check_blue, "check", genLoginRequestPendingIntent(true))
            // .addAction(R.drawable.close_red, "close", genLoginRequestPendingIntent(false))
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }

    // ══════════════════════════════════════════════════════════════════════
    // WebSocket / JWT management
    // ══════════════════════════════════════════════════════════════════════

    private fun authenticateDevice() {
        if (isAuthenticating) return
        isAuthenticating = true

        val deviceId = getDeviceUniqueId()
        android.util.Log.d("JWT", "Authenticating device: $deviceId")

        val client = okhttp3.OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = org.json.JSONObject().apply {
            put("device_id", deviceId)
        }.toString().toRequestBody(mediaType)

        val request = okhttp3.Request.Builder()
            .url(authUrl)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e("JWT", "Authentication failed: ${e.message}")
                isAuthenticating = false
                wsHandler.postDelayed({ authenticateDevice() }, 5000L)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                isAuthenticating = false
                try {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        val json = org.json.JSONObject(responseBody)
                        if (json.optBoolean("success", false)) {
                            jwtToken = json.optString("token", "")
                            android.util.Log.d("JWT", "Authentication successful, token received")
                            connectWebSocket()
                        } else {
                            android.util.Log.e("JWT", "Authentication failed: ${json.optString("message", "Unknown error")}")
                            wsHandler.postDelayed({ authenticateDevice() }, 5000L)
                        }
                    } else {
                        android.util.Log.e("JWT", "Authentication failed: HTTP ${response.code}")
                        wsHandler.postDelayed({ authenticateDevice() }, 5000L)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("JWT", "Failed to parse auth response", e)
                    wsHandler.postDelayed({ authenticateDevice() }, 5000L)
                }
            }
        })
    }

    private fun connectWebSocket() {
        if (jwtToken.isEmpty()) {
            android.util.Log.d("WebSocket", "No JWT token, authenticating first...")
            authenticateDevice()
            return
        }

        wsOkHttpClient = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $jwtToken")
            .build()

        wsClient = wsOkHttpClient?.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                wsReconnectAttempts = 0
                android.util.Log.d("WebSocket", "Connected to $wsUrl")
                val deviceId = getDeviceUniqueId()
                val networkType = getNetworkType()
                val registerMsg = org.json.JSONObject()
                registerMsg.put("type", "register_android")
                registerMsg.put("device_id", deviceId)
                registerMsg.put("network_type", networkType)
                webSocket.send(registerMsg.toString())
                android.util.Log.d("WebSocket", "Registered device: $deviceId, Network: $networkType")
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                android.util.Log.d("WebSocket", "Received: $text")
                try {
                    val json = org.json.JSONObject(text)
                    when (json.optString("type")) {
                        "registration_success" ->
                            android.util.Log.d("WebSocket", "Registration successful for: ${json.optString("device_id")}")
                        "start_session"    -> handleStartSession(json, webSocket)
                        "destroy_session"  -> handleDestroySession(json, webSocket)
                        "get_session_info" -> handleGetSessionInfo(json, webSocket)
                        "get_device_info"  -> handleGetDeviceInfo(json, webSocket)
                        "extend_lease"     -> handleExtendLease(json, webSocket)
                        "reboot"           -> handleReboot(json, webSocket)
                        "restart_remote"   -> handleRestartRemote(json, webSocket)
                        "clear_cache"      -> handleClearCache(json, webSocket)
                        else -> android.util.Log.w("WebSocket", "Unknown command: ${json.optString("type")}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocket", "Failed to parse message", e)
                }
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                android.util.Log.d("WebSocket", "Closing: $reason")
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                android.util.Log.d("WebSocket", "Closed: $reason")
                attemptReconnect()
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e("WebSocket", "Error: ${t.message}", t)
                attemptReconnect()
            }
        })
    }

    private fun attemptReconnect() {
        if (!wsShouldReconnect) return
        wsReconnectAttempts++
        val delay = (wsReconnectBaseDelay * Math.pow(2.0, (wsReconnectAttempts - 1).toDouble()))
            .toLong().coerceAtMost(wsReconnectMaxDelay)
        android.util.Log.d("WebSocket", "Reconnecting in ${delay}ms (attempt $wsReconnectAttempts)")
        jwtToken = ""
        wsHandler.postDelayed({ authenticateDevice() }, delay)
    }

    private fun disconnectWebSocket() {
        wsShouldReconnect = false
        wsClient?.close(1000, null)
        wsOkHttpClient?.dispatcher?.executorService?.shutdown()
        wsClient = null
        wsOkHttpClient = null
    }

    // ── WebSocket command handlers ─────────────────────────────────────────

    private fun handleStartSession(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val sessionId = System.currentTimeMillis().toString()
        val requestId = json.optString("request_id")
        isSessionActive = true

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                MainActivity.flutterMethodChannel?.invokeMethod(
                    "mainUpdateTemporaryPassword", null,
                    object : io.flutter.plugin.common.MethodChannel.Result {
                        override fun success(result: Any?) {
                            getServerPasswordWithRetry(previous = lastTempPasswordSent) { finalPassword ->
                                getRustdeskIdWithRetry { rustId ->
                                    val safePassword = finalPassword.trim()
                                    val resp = org.json.JSONObject()
                                    resp.put("type", "session_started")
                                    resp.put("session_id", sessionId)
                                    resp.put("temporary_password", safePassword)
                                    resp.put("rustdesk_id", rustId)
                                    if (requestId.isNotEmpty()) resp.put("request_id", requestId)
                                    resp.put("is_session_active", true)
                                    ws.send(resp.toString())
                                    lastTempPasswordSent = safePassword
                                    android.util.Log.d("WebSocket", "Session started: $sessionId pw=$safePassword id=$rustId")
                                }
                            }
                        }
                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                            sendFallbackResponse(ws, sessionId, "", requestId)
                        }
                        override fun notImplemented() {
                            sendFallbackResponse(ws, sessionId, "", requestId)
                        }
                    })
            } catch (e: Exception) {
                android.util.Log.e("WebSocket", "Exception in handleStartSession", e)
                sendFallbackResponse(ws, sessionId, "", requestId)
            }
        }
    }

    private fun handleDestroySession(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val sessionId = json.optString("session_id")
        val requestId = json.optString("request_id")
        isSessionActive = false
        val resp = org.json.JSONObject()
        resp.put("type", "session_destroyed")
        resp.put("session_id", sessionId)
        if (requestId.isNotEmpty()) resp.put("request_id", requestId)
        resp.put("is_session_active", false)
        ws.send(resp.toString())
        android.util.Log.d("WebSocket", "Session destroyed: $sessionId")

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                MainActivity.flutterMethodChannel?.invokeMethod("close_current_session", null)
            } catch (e: Exception) {
                android.util.Log.e("WebSocket", "Failed to invoke close_current_session", e)
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                MainActivity.flutterMethodChannel?.invokeMethod("mainUpdateTemporaryPassword", null)
                lastTempPasswordSent = ""
            } catch (e: Exception) {
                android.util.Log.e("WebSocket", "Failed to update password on session stop", e)
            }
        }
    }

    private fun handleGetSessionInfo(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val sessionId = json.optString("session_id")
        val requestId = json.optString("request_id")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            getServerPasswordWithRetry(previous = null) { finalPassword ->
                getRustdeskIdWithRetry { rustId ->
                    val safePassword = finalPassword.trim()
                    val resp = org.json.JSONObject()
                    resp.put("type", "session_info")
                    if (sessionId.isNotEmpty()) resp.put("session_id", sessionId)
                    resp.put("status", if (isSessionActive) "active" else "inactive")
                    resp.put("temporary_password", safePassword)
                    resp.put("rustdesk_id", rustId)
                    resp.put("is_session_active", isSessionActive)
                    if (requestId.isNotEmpty()) resp.put("request_id", requestId)
                    ws.send(resp.toString())
                }
            }
        }
    }

    private fun handleGetDeviceInfo(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val requestId = json.optString("request_id")
        val resp = org.json.JSONObject()
        resp.put("type", "device_info")
        resp.put("device_id", getDeviceUniqueId())
        resp.put("device_ip", getExternalIpAddress())
        resp.put("network_type", getNetworkType())
        resp.put("location", getDeviceLocation())
        resp.put("current_otp", getCurrentOtp())
        resp.put("manufacturer", android.os.Build.MANUFACTURER)
        resp.put("model", android.os.Build.MODEL)
        resp.put("version", android.os.Build.VERSION.SDK_INT)
        resp.put("serial", getDeviceSerial())
        resp.put("is_session_active", isSessionActive)
        if (requestId.isNotEmpty()) resp.put("request_id", requestId)
        ws.send(resp.toString())
        android.util.Log.d("WebSocket", "Device info sent")
    }

    private fun handleExtendLease(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val requestId = json.optString("request_id")
        val minutes = json.optInt("minutes", 0)
        val resp = org.json.JSONObject()
        resp.put("type", "lease_extended")
        resp.put("minutes", minutes)
        if (requestId.isNotEmpty()) resp.put("request_id", requestId)
        ws.send(resp.toString())
    }

    private fun handleReboot(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val requestId = json.optString("request_id")
        val resp = org.json.JSONObject()
        resp.put("type", "reboot_ok")
        if (requestId.isNotEmpty()) resp.put("request_id", requestId)
        ws.send(resp.toString())
    }

    private fun handleRestartRemote(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val requestId = json.optString("request_id")
        try { destroy() } catch (_: Exception) {}
        val resp = org.json.JSONObject()
        resp.put("type", "restart_remote_ok")
        if (requestId.isNotEmpty()) resp.put("request_id", requestId)
        ws.send(resp.toString())
    }

    private fun handleClearCache(json: org.json.JSONObject, ws: okhttp3.WebSocket) {
        val requestId = json.optString("request_id")
        val ok = try {
            cacheDir?.deleteRecursively()
            externalCacheDir?.deleteRecursively()
            true
        } catch (e: Exception) {
            android.util.Log.e("WebSocket", "Failed to clear cache", e)
            false
        }
        val resp = org.json.JSONObject()
        resp.put("type", if (ok) "clear_cache_ok" else "clear_cache_failed")
        if (requestId.isNotEmpty()) resp.put("request_id", requestId)
        ws.send(resp.toString())
    }

    private fun sendFallbackResponse(ws: okhttp3.WebSocket, sessionId: String, otp: String, requestId: String = "") {
        getRustdeskIdWithRetry(timeoutMs = 2000L, intervalMs = 100L) { rustId ->
            val resp = org.json.JSONObject()
            resp.put("type", "session_started")
            resp.put("session_id", sessionId)
            resp.put("temporary_password", otp)
            resp.put("rustdesk_id", rustId)
            if (requestId.isNotEmpty()) resp.put("request_id", requestId)
            resp.put("is_session_active", true)
            ws.send(resp.toString())
        }
    }

    // ── Retry helpers ──────────────────────────────────────────────────────

    private fun getServerPasswordWithRetry(
        previous: String? = null,
        timeoutMs: Long = 5000L,
        intervalMs: Long = 150L,
        callback: (String) -> Unit
    ) {
        val start = System.currentTimeMillis()
        fun attempt() {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "get_server_password", null,
                object : io.flutter.plugin.common.MethodChannel.Result {
                    override fun success(passwordResult: Any?) {
                        val pwd = (passwordResult as? String)?.trim() ?: ""
                        val fresh = pwd.isNotEmpty() && (previous == null || pwd != previous)
                        if (fresh) {
                            callback(pwd)
                        } else if (System.currentTimeMillis() - start >= timeoutMs) {
                            callback(pwd)
                        } else {
                            wsHandler.postDelayed({ attempt() }, intervalMs)
                        }
                    }
                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        if (System.currentTimeMillis() - start >= timeoutMs) callback("")
                        else wsHandler.postDelayed({ attempt() }, intervalMs)
                    }
                    override fun notImplemented() {
                        if (System.currentTimeMillis() - start >= timeoutMs) callback("")
                        else wsHandler.postDelayed({ attempt() }, intervalMs)
                    }
                })
        }
        attempt()
    }

    private fun getRustdeskIdWithRetry(
        timeoutMs: Long = 5000L,
        intervalMs: Long = 150L,
        callback: (String) -> Unit
    ) {
        val start = System.currentTimeMillis()
        fun attempt() {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "get_rustdesk_id", null,
                object : io.flutter.plugin.common.MethodChannel.Result {
                    override fun success(result: Any?) {
                        val id = (result as? String)?.trim().orEmpty()
                        if (id.isNotEmpty()) callback(id)
                        else if (System.currentTimeMillis() - start >= timeoutMs) callback("")
                        else wsHandler.postDelayed({ attempt() }, intervalMs)
                    }
                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        if (System.currentTimeMillis() - start >= timeoutMs) callback("")
                        else wsHandler.postDelayed({ attempt() }, intervalMs)
                    }
                    override fun notImplemented() { callback("") }
                })
        }
        attempt()
    }

    // ── Device / network utilities ─────────────────────────────────────────

    internal fun getDeviceUniqueId(): String {
        val serial = getDeviceSerial().trim()
        if (serial.isNotEmpty() && !serial.equals("unknown", ignoreCase = true)) return serial
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )?.trim().orEmpty()
        if (androidId.isNotEmpty() && !androidId.equals("unknown", ignoreCase = true)) return androidId
        val prefs = getSharedPreferences("rd_device_prefs", android.content.Context.MODE_PRIVATE)
        var uuid = prefs.getString("device_uuid", null)
        if (uuid.isNullOrEmpty()) {
            uuid = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", uuid).apply()
        }
        return uuid
    }

    private fun getDeviceSerial(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try { android.os.Build.getSerial() } catch (_: SecurityException) { "" }
            } else {
                @Suppress("DEPRECATION")
                (android.os.Build.SERIAL ?: "")
            }
        } catch (_: Exception) { "" }
    }

    private fun getCurrentOtp(): String {
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "default"
        val seed = (System.currentTimeMillis().toString() + deviceId).hashCode()
        currentOtp = String.format("%06d", kotlin.math.abs(seed) % 1000000)
        return currentOtp
    }

    private fun getNetworkType(): String {
        return try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return "No Connection"
                val caps = cm.getNetworkCapabilities(net) ?: return "Unknown"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getCellularNetworkType()
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)-> "Bluetooth"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)      -> "VPN"
                    else -> "Unknown"
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                if (info == null || !info.isConnected) return "No Connection"
                @Suppress("DEPRECATION")
                when (info.type) {
                    ConnectivityManager.TYPE_WIFI      -> "WiFi"
                    ConnectivityManager.TYPE_MOBILE    -> getCellularNetworkType()
                    ConnectivityManager.TYPE_ETHERNET  -> "Ethernet"
                    ConnectivityManager.TYPE_BLUETOOTH -> "Bluetooth"
                    else -> "Unknown"
                }
            }
        } catch (e: Exception) { "Error" }
    }

    private fun getCellularNetworkType(): String {
        return try {
            val tm = getSystemService(android.content.Context.TELEPHONY_SERVICE) as TelephonyManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_NR    -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE   -> "LTE"
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_UMTS  -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS,
                    TelephonyManager.NETWORK_TYPE_CDMA,
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
                    else -> "Mobile"
                }
            } else {
                @Suppress("DEPRECATION")
                when (tm.networkType) {
                    TelephonyManager.NETWORK_TYPE_LTE   -> "LTE"
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_UMTS  -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS,
                    TelephonyManager.NETWORK_TYPE_CDMA,
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
                    else -> "Mobile"
                }
            }
        } catch (e: Exception) { "Mobile" }
    }

    private fun getExternalIpAddress(): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val urls = listOf("https://api.ipify.org", "https://checkip.amazonaws.com", "https://ifconfig.me/ip")
            for (u in urls) {
                try {
                    val req = okhttp3.Request.Builder().url(u).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()?.trim().orEmpty()
                            if (body.isNotEmpty() && (body.contains('.') || body.contains(':'))) return body
                        }
                    }
                } catch (_: Exception) {}
            }
            "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                for (addr in java.util.Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null && sAddr.indexOf(':') < 0 && !sAddr.startsWith("169.254")) return sAddr
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocket", "Error getting local IP", e)
        }
        return "unknown"
    }

    private fun getDeviceLocation(): org.json.JSONObject {
        val obj = org.json.JSONObject()
        try {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    obj.put("latitude", loc.latitude)
                    obj.put("longitude", loc.longitude)
                    obj.put("accuracy", loc.accuracy)
                    obj.put("timestamp", loc.time)
                } else {
                    obj.put("status", "location_not_available")
                }
            } else {
                obj.put("status", "location_disabled")
            }
        } catch (e: SecurityException) {
            obj.put("status", "permission_denied")
        } catch (e: Exception) {
            obj.put("status", "error")
        }
        return obj
    }
}
