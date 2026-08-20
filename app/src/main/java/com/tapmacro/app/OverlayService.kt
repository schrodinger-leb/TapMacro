package com.tapmacro.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var wm: WindowManager

    // control bubble
    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // full-screen invisible touch-capture layer, only added while recording
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var isMinimized = false

    private val handler = Handler(Looper.getMainLooper())

    private var isRecording = false
    private var isPlaying = false
    private val recordedEvents = mutableListOf<TapEvent>()
    private var lastEventTime = 0L

    private var speedMultiplier = 1.0f

    private enum class RepeatMode { NONE, CONTINUOUS, COUNT }
    private var repeatMode = RepeatMode.NONE
    private var repeatTarget = 1
    private var currentLoop = 1

    // Fail-safe: turning the screen off (power button, timeout, anything) kills
    // any active recording/playback immediately, so a stuck macro can never
    // keep tapping unattended once the screen is off.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                failSafeStop()
            }
        }
    }

    private fun failSafeStop() {
        val wasRecording = isRecording
        val wasPlaying = isPlaying
        cancelRecordingAndPlayback()
        if (wasRecording || wasPlaying) {
            setStatus("Stopped (screen off)")
        }
    }

    /** Cancels whichever of recording/playback is active, without changing status text. */
    private fun cancelRecordingAndPlayback() {
        if (isRecording) {
            stopRecording()
        }
        if (isPlaying) {
            isPlaying = false
            handler.removeCallbacksAndMessages(null)
        }
    }

    /** Stop button: cancels recording if recording, or cancels playback if playing. */
    private fun stopButtonPressed() {
        if (isRecording) {
            stopRecording()
            return
        }
        if (isPlaying) {
            isPlaying = false
            handler.removeCallbacksAndMessages(null)
            setStatus("Playback stopped")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addControlBubble()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    private fun startForegroundNotification() {
        val channelId = "tapmacro_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "DaehunTask Controller", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DaehunTask running")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1, notification)
    }

    // ---------- Control bubble ----------

    private fun addControlBubble() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_control, null)
        controlView = view

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200
        controlParams = params
        wm.addView(view, params)

        statusText = view.findViewById(R.id.statusText)

        // drag the white bar to move the controller
        val dragHandle = view.findViewById<View>(R.id.dragHandle)
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                return true
            }
        })

        view.findViewById<Button>(R.id.btnRecord).setOnClickListener { startRecording() }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener { stopButtonPressed() }
        view.findViewById<Button>(R.id.btnPlay).setOnClickListener { playRecording() }
        view.findViewById<Button>(R.id.btnMore).setOnClickListener { showMoreMenu() }

        val buttonsRow = view.findViewById<View>(R.id.buttonsRow)
        val statusView = view.findViewById<View>(R.id.statusText)
        val minimizeButton = view.findViewById<Button>(R.id.btnMinimize)
        minimizeButton.setOnClickListener {
            isMinimized = !isMinimized
            buttonsRow.visibility = if (isMinimized) View.GONE else View.VISIBLE
            statusView.visibility = if (isMinimized) View.GONE else View.VISIBLE
            // Arrow flips direction to show which way it'll act next: pointing
            // left to collapse, pointing right to expand back out.
            minimizeButton.text = if (isMinimized) "\u25B6" else "\u25C0"
            controlParams?.let {
                try { wm.updateViewLayout(view, it) } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    private fun overlayDialogType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------- More menu: Speed / Save / Open / Close ----------

    private fun showMoreMenu() {
        val repeatLabel = when (repeatMode) {
            RepeatMode.NONE -> "off"
            RepeatMode.CONTINUOUS -> "continuous"
            RepeatMode.COUNT -> "$repeatTarget times"
        }
        val items = arrayOf(
            "Speed (currently ${speedMultiplier}x)",
            "Repeat (currently $repeatLabel)",
            "Save recording",
            "Open saved macro",
            "Close controller"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("More")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptSpeedMenu()
                    1 -> promptRepeatMenu()
                    2 -> saveRecording()
                    3 -> showOpenMenu()
                    4 -> stopSelf()
                }
            }
            .create()
        dialog.window?.setType(overlayDialogType())
        dialog.show()
    }

    private fun promptRepeatMenu() {
        val options = arrayOf("Off (play once)", "Repeat continuously", "Repeat a number of times")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Repeat")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { repeatMode = RepeatMode.NONE; setStatus("Repeat: off") }
                    1 -> { repeatMode = RepeatMode.CONTINUOUS; setStatus("Repeat: continuous") }
                    2 -> promptRepeatCount()
                }
            }
            .create()
        dialog.window?.setType(overlayDialogType())
        dialog.show()
    }

    private fun promptRepeatCount() {
        val editText = EditText(this)
        editText.hint = "e.g. 10"
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        val dialog = AlertDialog.Builder(this)
            .setTitle("Repeat how many times?")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val n = editText.text.toString().toIntOrNull()
                if (n != null && n > 0) {
                    repeatMode = RepeatMode.COUNT
                    repeatTarget = n
                    setStatus("Repeat: $n times")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(overlayDialogType())
        dialog.show()
    }

    private fun promptSpeedMenu() {
        val options = arrayOf("1x", "2x", "5x", "Custom")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "1x" -> { speedMultiplier = 1.0f; setStatus("Speed: 1x") }
                    "2x" -> { speedMultiplier = 2.0f; setStatus("Speed: 2x") }
                    "5x" -> { speedMultiplier = 5.0f; setStatus("Speed: 5x") }
                    "Custom" -> promptCustomSpeed()
                }
            }
            .create()
        dialog.window?.setType(overlayDialogType())
        dialog.show()
    }

    private fun promptCustomSpeed() {
        // Simple overlay-safe input: EditText inside a system-alert dialog window.
        val editText = EditText(this)
        editText.hint = "e.g. 3.5"
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        val dialog = AlertDialog.Builder(this)
            .setTitle("Custom speed multiplier")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val v = editText.text.toString().toFloatOrNull()
                if (v != null && v > 0f) speedMultiplier = v
                setStatus("Speed: ${speedMultiplier}x")
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setType(overlayDialogType())
        dialog.show()
    }

    private fun showOpenMenu() {
        val names = MacroStorage.list(this)
        if (names.isEmpty()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Open saved macro")
                .setMessage("No saved macros yet. Record something and use Save first.")
                .setPositiveButton("OK", null)
                .create()
            dialog.window?.setType(overlayDialogType())
            dialog.show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Open saved macro")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                val loaded = MacroStorage.load(this, name)
                recordedEvents.clear()
                recordedEvents.addAll(loaded)
                setStatus("Loaded $name (${loaded.size} taps)")
            }
            .create()
        dialog.window?.setType(overlayDialogType())
        dialog.show()
    }

    private fun setStatus(text: String) {
        handler.post { statusText?.text = text }
    }

    /** Formats elapsed time as H:M:S with no zero-padding, e.g. "0:1:19". */
    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "$hours:$minutes:$seconds"
    }

    // ---------- Recording ----------

    private var recordingStartTime = 0L
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsedMs = SystemClock.uptimeMillis() - recordingStartTime
            setStatus("Recording ${formatElapsed(elapsedMs)} \u00b7 ${recordedEvents.size} taps")
            handler.postDelayed(this, 250)
        }
    }

    private fun startRecording() {
        if (isRecording || isPlaying) return
        if (TapAccessibilityService.instance == null) {
            setStatus("Enable Accessibility Service first")
            return
        }
        recordedEvents.clear()
        isRecording = true
        lastEventTime = SystemClock.uptimeMillis()
        recordingStartTime = lastEventTime
        addCaptureOverlay()
        // Re-add the control bubble so it renders ABOVE the full-screen capture
        // layer -- otherwise the capture layer swallows taps on Stop/Play/etc.
        controlView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) { /* not attached - ignore */ }
            try {
                wm.addView(it, controlParams)
            } catch (e: Exception) { /* already attached - ignore */ }
        }
        handler.post(recordingTicker)
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacks(recordingTicker)
        removeCaptureOverlay()
        val elapsed = formatElapsed(SystemClock.uptimeMillis() - recordingStartTime)
        setStatus("Recorded ${recordedEvents.size} taps ($elapsed)")
    }

    private fun addCaptureOverlay() {
        if (captureView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayDialogType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val view = View(this)
        view.setOnTouchListener { _, event ->
            if (!isRecording) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    // Use the MotionEvent's own timestamps (same clock as
                    // SystemClock.uptimeMillis) and measure the gap from the
                    // previous tap's release to THIS tap's press -- not to
                    // this tap's own release, which would fold each tap's
                    // hold-duration into the delay and cause drift.
                    val downTime = event.downTime
                    val upTime = event.eventTime
                    val duration = (upTime - downTime).coerceAtLeast(20L)
                    val delay = (downTime - lastEventTime).coerceAtLeast(0L)
                    lastEventTime = upTime
                    val x = event.rawX
                    val y = event.rawY
                    recordedEvents.add(TapEvent(x, y, delay, duration))
                    // Instant, deterministic confirmation of what was just
                    // recorded -- a numbered marker at the tap location. We
                    // deliberately don't try to echo the tap into the real
                    // app anymore: that required briefly consuming, then
                    // replaying, every touch, which is exactly what caused
                    // the freezing and the unreliable double-registering.
                    // This marker approach can't race or get stuck, because
                    // there's no window state to pause/resume at all.
                    showTapMarker(x, y, recordedEvents.size)
                    setStatus("Recording ${formatElapsed(SystemClock.uptimeMillis() - recordingStartTime)} \u00b7 ${recordedEvents.size} taps")
                }
            }
            true
        }
        captureView = view
        captureParams = params
        wm.addView(view, params)
    }

    /** Instant numbered marker shown at a recorded tap's location -- deterministic
     *  confirmation with no window-state juggling to race or get stuck on. */
    private fun showTapMarker(x: Float, y: Float, tapNumber: Int) {
        val size = 100
        val marker = TextView(this)
        marker.text = tapNumber.toString()
        marker.setTextColor(android.graphics.Color.WHITE)
        marker.textSize = 16f
        marker.gravity = Gravity.CENTER
        marker.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xCCE53935.toInt())
        }
        val markerParams = WindowManager.LayoutParams(
            size, size,
            overlayDialogType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        markerParams.gravity = Gravity.TOP or Gravity.START
        markerParams.x = (x - size / 2f).toInt()
        markerParams.y = (y - size / 2f).toInt()

        try {
            wm.addView(marker, markerParams)
            marker.animate().alpha(0f).setDuration(500).setStartDelay(300).withEndAction {
                try { wm.removeView(marker) } catch (e: Exception) { /* already gone */ }
            }.start()
        } catch (e: Exception) {
            // marker is purely cosmetic - never let it crash recording
        }
    }

    private fun removeCaptureOverlay() {
        try {
            captureView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            // view may already be detached (e.g. after a crash/restart) - safe to ignore
        }
        captureView = null
        captureParams = null
    }

    // ---------- Playback ----------

    private var playbackStartTime = 0L
    private val playbackTicker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val elapsedMs = SystemClock.uptimeMillis() - playbackStartTime
            val loopInfo = when (repeatMode) {
                RepeatMode.NONE -> ""
                RepeatMode.CONTINUOUS -> " \u00b7 loop $currentLoop"
                RepeatMode.COUNT -> " \u00b7 loop $currentLoop/$repeatTarget"
            }
            setStatus("Playing ${formatElapsed(elapsedMs)}$loopInfo")
            handler.postDelayed(this, 250)
        }
    }

    private fun playRecording() {
        if (isRecording || isPlaying || recordedEvents.isEmpty()) return
        isPlaying = true
        currentLoop = 1
        playbackStartTime = SystemClock.uptimeMillis()
        handler.post(playbackTicker)
        playFrom(0)
    }

    private fun playFrom(index: Int) {
        if (index >= recordedEvents.size) {
            val shouldRepeat = isPlaying && when (repeatMode) {
                RepeatMode.NONE -> false
                RepeatMode.CONTINUOUS -> true
                RepeatMode.COUNT -> currentLoop < repeatTarget
            }
            if (shouldRepeat) {
                currentLoop++
                playFrom(0)
                return
            }
            isPlaying = false
            handler.removeCallbacks(playbackTicker)
            val elapsed = formatElapsed(SystemClock.uptimeMillis() - playbackStartTime)
            val loopSuffix = if (repeatMode != RepeatMode.NONE) " \u00b7 $currentLoop loop(s)" else ""
            setStatus("Done ($elapsed)$loopSuffix")
            return
        }
        val ev = recordedEvents[index]
        // Speed multiplier scales BOTH the gap before this tap AND the tap's
        // own hold duration, so it actually changes the pace of playback
        // rather than just the gaps between taps.
        val scaledDelay = (ev.delayMs / speedMultiplier).toLong().coerceAtLeast(0L)
        val scaledDuration = (ev.durationMs / speedMultiplier).toLong().coerceAtLeast(16L)
        handler.postDelayed({
            TapAccessibilityService.instance?.performTap(ev.x, ev.y, scaledDuration) {
                playFrom(index + 1)
            } ?: run {
                setStatus("Accessibility service not enabled")
                isPlaying = false
                handler.removeCallbacks(playbackTicker)
            }
        }, scaledDelay)
    }

    // ---------- Save ----------

    private fun saveRecording() {
        if (recordedEvents.isEmpty()) {
            setStatus("Nothing to save")
            return
        }
        val existingCount = MacroStorage.list(this).size
        val nextNumber = existingCount + 1
        val dateStr = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        val name = "Macro $nextNumber [$dateStr]"
        MacroStorage.save(this, name, recordedEvents)
        setStatus("Saved as $name")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // already unregistered / never registered - safe to ignore
        }
        removeCaptureOverlay()
        controlView?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* already detached - ignore */ }
        }
        controlView = null
    }
}
