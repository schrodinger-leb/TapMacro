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

    private val handler = Handler(Looper.getMainLooper())

    private var isRecording = false
    private var isPlaying = false
    private val recordedEvents = mutableListOf<TapEvent>()
    private var lastEventTime = 0L
    private var ignoreTouchesUntil = 0L

    private var speedMultiplier = 1.0f
    private val speedOptions = listOf("1x", "2x", "5x", "Custom")

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

        // drag to move
        val dragHandle = view.findViewById<TextView>(R.id.dragHandle)
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

        // speed spinner
        val spinner = view.findViewById<Spinner>(R.id.spinnerSpeed)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speedOptions)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                when (speedOptions[pos]) {
                    "1x" -> speedMultiplier = 1.0f
                    "2x" -> speedMultiplier = 2.0f
                    "5x" -> speedMultiplier = 5.0f
                    "Custom" -> promptCustomSpeed()
                }
                setStatus("Speed: ${speedMultiplier}x")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        view.findViewById<Button>(R.id.btnRecord).setOnClickListener { startRecording() }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener { stopButtonPressed() }
        view.findViewById<Button>(R.id.btnPlay).setOnClickListener { playRecording() }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener { saveRecording() }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { stopSelf() }
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

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    private fun setStatus(text: String) {
        handler.post { statusText?.text = text }
    }

    // ---------- Recording ----------

    private var recordingStartTime = 0L
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsedMs = SystemClock.uptimeMillis() - recordingStartTime
            setStatus("Recording: ${formatElapsed(elapsedMs)}")
            handler.postDelayed(this, 500)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
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
        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
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
                    // Ignore anything arriving in the short window right after
                    // we resume capture from echoing a tap -- this is almost
                    // always the tail of our own synthetic echo gesture
                    // bleeding back into our own window, not a new real tap.
                    // Without this, a single physical tap could get recorded
                    // twice and play back as a rapid double/triple-tap.
                    if (SystemClock.uptimeMillis() < ignoreTouchesUntil) {
                        return@setOnTouchListener true
                    }

                    // Use the MotionEvent's own timestamps (same clock as
                    // SystemClock.uptimeMillis) rather than re-measuring with
                    // our own calls, and measure the gap from the previous
                    // tap's release to THIS tap's press -- not to this tap's
                    // release, which was accidentally folding each tap's
                    // hold-duration into the delay and causing drift.
                    val downTime = event.downTime
                    val upTime = event.eventTime
                    val duration = (upTime - downTime).coerceAtLeast(20L)
                    val delay = (downTime - lastEventTime).coerceAtLeast(0L)
                    lastEventTime = upTime
                    val x = event.rawX
                    val y = event.rawY
                    recordedEvents.add(TapEvent(x, y, delay, duration))
                    // To actually show the tap landing on the app underneath, we
                    // briefly make our own capture window transparent to touch
                    // before echoing the tap -- otherwise our own invisible
                    // window is what's on top and it just intercepts its own
                    // echo, which is why nothing appeared to happen live
                    // before. We restore normal capture once the echo completes.
                    pauseCaptureOverlay()
                    val service = TapAccessibilityService.instance
                    if (service != null) {
                        // Use a short, fixed echo duration for live feedback --
                        // we don't need to replicate your exact hold time here
                        // (that happens for real during Play), and echoing
                        // with the full hold time just adds visible lag before
                        // you see anything happen.
                        val echoDuration = 60L
                        service.performTap(x, y, echoDuration) {
                            // Give the synthetic gesture's own touch stream a
                            // moment to fully settle before listening again.
                            handler.postDelayed({
                                ignoreTouchesUntil = SystemClock.uptimeMillis() + 150L
                                resumeCaptureOverlay()
                            }, 40L)
                        }
                    } else {
                        resumeCaptureOverlay()
                    }
                }
            }
            true
        }
        captureView = view
        captureParams = params
        wm.addView(view, params)
    }

    /** Makes the capture window transparent to touch, letting the echoed tap
     *  reach the real app underneath, without the expense/flakiness of fully
     *  removing and re-adding the window on every single tap. */
    private fun pauseCaptureOverlay() {
        val v = captureView ?: return
        val p = captureParams ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                wm.updateViewLayout(v, p)
            } catch (e: Exception) { /* ignore - window may be mid-transition */ }
        }
    }

    /** Restores normal touch interception on the capture window. */
    private fun resumeCaptureOverlay() {
        if (!isRecording) return
        val v = captureView ?: return
        val p = captureParams ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                wm.updateViewLayout(v, p)
            } catch (e: Exception) { /* ignore - window may be mid-transition */ }
        }
    }

    /** Purely cosmetic feedback dot shown at a recorded tap's location. */
    private fun showTapMarker(x: Float, y: Float) {
        val size = 90
        val marker = View(this)
        marker.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x99FF5252.toInt())
        }
        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
        val markerParams = WindowManager.LayoutParams(
            size, size,
            overlayType,
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
            marker.animate().alpha(0f).setDuration(350).withEndAction {
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

    private fun playRecording() {
        if (isRecording || isPlaying || recordedEvents.isEmpty()) return
        isPlaying = true
        setStatus("Playing 0/${recordedEvents.size}")
        playFrom(0)
    }

    private fun playFrom(index: Int) {
        if (index >= recordedEvents.size) {
            isPlaying = false
            setStatus("Done")
            return
        }
        val ev = recordedEvents[index]
        val scaledDelay = (ev.delayMs / speedMultiplier).toLong().coerceAtLeast(0L)
        handler.postDelayed({
            TapAccessibilityService.instance?.performTap(ev.x, ev.y, ev.durationMs) {
                setStatus("Playing ${index + 1}/${recordedEvents.size}")
                playFrom(index + 1)
            } ?: run {
                setStatus("Accessibility service not enabled")
                isPlaying = false
            }
        }, scaledDelay)
    }

    // ---------- Save ----------

    private fun saveRecording() {
        if (recordedEvents.isEmpty()) {
            setStatus("Nothing to save")
            return
        }
        val name = "macro_${System.currentTimeMillis()}"
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
