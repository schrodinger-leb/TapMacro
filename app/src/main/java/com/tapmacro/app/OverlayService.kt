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
    private var isEchoing = false
    private var echoGeneration = 0

    private var speedMultiplier = 1.0f

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
    }

    private fun overlayDialogType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------- More menu: Speed / Save / Open / Close ----------

    private fun showMoreMenu() {
        val items = arrayOf(
            "Speed (currently ${speedMultiplier}x)",
            "Save recording",
            "Open saved macro",
            "Close controller"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("More")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptSpeedMenu()
                    1 -> saveRecording()
                    2 -> showOpenMenu()
                    3 -> stopSelf()
                }
            }
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
            setStatus("Recording ${formatElapsed(elapsedMs)}")
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
        isEchoing = false
        echoGeneration++ // invalidate any leftover pending callbacks from a past session
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
        echoGeneration++ // invalidate any in-flight echo callbacks/timeouts
        isEchoing = false
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
            // Hard block on ANY touch while an echo gesture is in flight --
            // this is a code-level guard, not a timing guess, so it can't
            // race with how fast the OS actually applies our touch-through
            // flag. Without this, the tail of our own echoed tap could sneak
            // back into our own window and get recorded as a second,
            // duplicate tap.
            if (isEchoing) {
                return@setOnTouchListener true
            }
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

                    // To actually show the tap landing on the app underneath, we
                    // briefly make our own capture window transparent to touch
                    // before echoing the tap -- otherwise our own invisible
                    // window is what's on top and it just intercepts its own
                    // echo. We restore normal capture once the echo completes.
                    isEchoing = true
                    pauseCaptureOverlay()
                    val myGeneration = ++echoGeneration
                    val service = TapAccessibilityService.instance
                    if (service != null) {
                        // Short, fixed echo duration for live feedback -- we
                        // don't need to replicate your exact hold time here
                        // (that happens for real during Play), and a long
                        // echo just adds visible lag before you see anything.
                        val echoDuration = 50L
                        service.performTap(x, y, echoDuration) {
                            if (myGeneration == echoGeneration) {
                                resumeCaptureOverlay()
                                handler.postDelayed({
                                    if (myGeneration == echoGeneration) isEchoing = false
                                }, 40L)
                            }
                        }
                        // Safety net: if the gesture callback never fires for
                        // any reason, don't let recording freeze forever --
                        // force-resume after a short timeout.
                        handler.postDelayed({
                            if (myGeneration == echoGeneration && isEchoing) {
                                resumeCaptureOverlay()
                                isEchoing = false
                            }
                        }, 500L)
                    } else {
                        resumeCaptureOverlay()
                        isEchoing = false
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
            setStatus("Playing ${formatElapsed(elapsedMs)}")
            handler.postDelayed(this, 250)
        }
    }

    private fun playRecording() {
        if (isRecording || isPlaying || recordedEvents.isEmpty()) return
        isPlaying = true
        playbackStartTime = SystemClock.uptimeMillis()
        handler.post(playbackTicker)
        playFrom(0)
    }

    private fun playFrom(index: Int) {
        if (index >= recordedEvents.size) {
            isPlaying = false
            handler.removeCallbacks(playbackTicker)
            val elapsed = formatElapsed(SystemClock.uptimeMillis() - playbackStartTime)
            setStatus("Done ($elapsed)")
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
