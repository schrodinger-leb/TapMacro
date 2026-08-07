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

        if (isRecording) {
            stopRecording()
        }
        if (isPlaying) {
            isPlaying = false
            handler.removeCallbacksAndMessages(null)
        }
        if (wasRecording || wasPlaying) {
            setStatus("Stopped (screen off)")
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
                channelId, "Tap Macro Controller", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tap Macro running")
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
        view.findViewById<Button>(R.id.btnStop).setOnClickListener { stopRecording() }
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
            wm.removeView(it)
            wm.addView(it, controlParams)
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
                MotionEvent.ACTION_DOWN -> {
                    view.setTag(event.eventTime) // remember down time
                }
                MotionEvent.ACTION_UP -> {
                    val downTime = view.getTag() as? Long ?: event.eventTime
                    val duration = (event.eventTime - downTime).coerceAtLeast(20L)
                    val now = SystemClock.uptimeMillis()
                    val delay = (now - lastEventTime).coerceAtLeast(0L)
                    lastEventTime = now
                    val x = event.rawX
                    val y = event.rawY
                    recordedEvents.add(TapEvent(x, y, delay, duration))
                    // echo the tap to the app underneath so the user sees it register live
                    TapAccessibilityService.instance?.performTap(x, y, duration)
                }
            }
            true
        }
        captureView = view
        captureParams = params
        wm.addView(view, params)
    }

    private fun removeCaptureOverlay() {
        captureView?.let { wm.removeView(it) }
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
        controlView?.let { wm.removeView(it) }
        controlView = null
    }
}
