package com.pranav.morninggate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt

// ---------- snapshot types the gate screen renders ----------

data class CritAction(val label: String, val pkg: String?, val tapTag: Boolean)

data class CritView(
    val index: Int,
    val type: CType,
    val done: Boolean,
    val text: String,
    val actions: List<CritAction>
)

data class TaskView(
    val id: String,
    val name: String,
    val done: Boolean,
    val crits: List<CritView>
)

data class GateSnapshot(
    val tasks: List<TaskView>,
    val doneTasks: Int,
    val totalTasks: Int,
    val releaseAtMs: Long,
    val warning: String?,
    val screenOn: Boolean
)

/**
 * Runs while the morning gate is active: shows the gate overlay, tracks
 * verification progress (dwell / steps / NFC / app time) and releases the
 * phone once every task is done, when the window ends, or after the tap escape.
 */
class GateService : Service() {

    companion object {
        private const val ACTION_START = "com.pranav.morninggate.action.START"
        private const val ACTION_STOP = "com.pranav.morninggate.action.STOP"
        private const val ACTION_EXIT_TODAY = "com.pranav.morninggate.action.EXIT_TODAY"
        private const val EXTRA_FORCE = "force"
        private const val EXTRA_AUTO_STOP_MS = "auto_stop_ms"

        private const val CHANNEL_ID = "morning_gate"
        private const val NOTIF_ID = 1

        private const val MOTION_GRACE_MS = 5_000L
        private const val TICK_MS = 1_000L
        private const val SELF_CHECK_MS = 20_000L
        private const val POLL_MS = 1_500L
        private const val PERSIST_EVERY_MS = 15_000L
        private const val SETTINGS_GRACE_MS = 45_000L

        @Volatile
        private var instance: GateService? = null

        fun start(context: Context, force: Boolean = false, autoStopMs: Long = 0L) {
            val i = Intent(context, GateService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORCE, force)
                .putExtra(EXTRA_AUTO_STOP_MS, autoStopMs)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GateService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun isRunning(): Boolean = instance != null

        fun snapshotNow(): GateSnapshot? = instance?.snapshot()

        fun onForegroundPackage(pkg: String?) {
            instance?.handleForeground(pkg)
        }

        fun requestRefresh() {
            instance?.overlay?.refresh()
        }

        fun onAuxResume() {
            instance?.setAux(true)
        }

        fun onAuxPause() {
            instance?.setAux(false)
        }

        fun onAuxDestroy() {
            instance?.setAux(false)
        }

        /** Launches the NFC activity slightly above the gate (overlay yields to it). */
        fun launchAux(context: Context, intent: Intent) {
            instance?.setAux(true)
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }

        /** The user needs to visit a system page (e.g. NFC settings); open a short grace window. */
        fun openSettings(context: Context, intent: Intent) {
            instance?.let {
                it.settingsGraceUntilMs = SystemClock.elapsedRealtime() + SETTINGS_GRACE_MS
                Logx.add("settings allowed briefly")
            }
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: GateShellView? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var overlayShown = false
    private var forced = false
    private var auxActive = false
    private var currentForeground: String? = null
    private var firstUsagePoll = true
    private var releasing = false
    private var settingsGraceUntilMs = 0L
    private var lastLoggedBlocked: String? = null

    // idle / dwell tracking
    private var screenOn = true
    private var lastMotionMs = 0L
    private var lastIdle = false
    private var lastTickMs = 0L
    private var lastPersistMs = 0L

    // sensors
    private var sensorManager: SensorManager? = null
    private var significantMotion: Sensor? = null
    private var stepSensor: Sensor? = null
    private var stepWatcherActive = false
    private val stepSamples = java.util.ArrayDeque<LongArray>() // [elapsedRealtimeMs, totalSteps]
    private var lastTotalSteps = -1L

    // usage polling
    private var lastPollMs = 0L
    private var lastPollFg: String? = null

    private var warningCache: String? = null

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        Gate.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_EXIT_TODAY -> {
                releaseToday()
                return START_NOT_STICKY
            }
            else -> {
                forced = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
                val autoStopMs = intent?.getLongExtra(EXTRA_AUTO_STOP_MS, 0L) ?: 0L

                if (!forced && !Gate.activeNow()) {
                    Logx.add("start ignored (gate inactive)")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    Logx.add("start aborted: no overlay permission")
                    Toast.makeText(this, "Morning Gate: allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                startInForeground()
                ensureOverlay()
                registerScreenReceiver()
                setupSensors()

                val pm = getSystemService(PowerManager::class.java)
                screenOn = pm?.isInteractive ?: true
                lastTickMs = SystemClock.elapsedRealtime()
                lastMotionMs = lastTickMs
                lastIdle = false
                releasing = false

                ScheduleEngine.scheduleHeartbeat(this)
                handler.removeCallbacks(selfCheck)
                handler.post(selfCheck)
                handler.removeCallbacks(ticker)
                handler.post(ticker)
                handler.removeCallbacks(usagePoll)
                handler.post(usagePoll)
                updateWarning()

                if (autoStopMs > 0L) {
                    handler.postDelayed({
                        Logx.add("preview timer ended")
                        shutdown()
                    }, autoStopMs)
                }

                Logx.add(if (forced) "preview shown" else "gate started")
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        unregisterScreenReceiver()
        teardownSensors()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- foreground notification ----------

    private fun startInForeground() {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Morning gate", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps Morning Gate running until your tasks are verified"
            ch.setShowBadge(false)
            ch.enableVibration(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val tasks = Gate.tasks
        val done = tasks.count { Gate.taskDone(it) }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle("Morning Gate · $done of ${tasks.size} tasks done")
            .setContentText("Releases at ${Format.minutes(Prefs.endMinutes)} no matter what")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    // ---------- overlay ----------

    private fun ensureOverlay() {
        if (overlay != null) return
        val v = GateShellView(this)
        v.onExitRequested = { releaseToday() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            params.fitInsetsTypes = 0
        }
        try {
            wm?.addView(v, params)
            overlay = v
            overlayLp = params
            overlayShown = true
        } catch (t: Throwable) {
            Logx.add("overlay add failed: ${t.javaClass.simpleName}")
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        overlay = null
        overlayLp = null
        overlayShown = false
    }

    private fun setOverlayShown(shown: Boolean) {
        val v = overlay ?: return
        val p = overlayLp ?: return
        if (shown == overlayShown) return
        overlayShown = shown
        if (shown) {
            p.flags = p.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            v.visibility = View.VISIBLE
            v.refresh()
        } else {
            p.flags = p.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            v.visibility = View.GONE
        }
        try {
            wm?.updateViewLayout(v, p)
        } catch (_: Throwable) {
        }
    }

    private fun setAux(active: Boolean) {
        auxActive = active
        if (active) {
            setOverlayShown(false)
        } else {
            handleForeground(currentForeground ?: packageName)
        }
    }

    /**
     * The policy: calls always keep the screen, apps an incomplete app-time
     * task still needs are released, everything else gets covered.
     */
    private fun handleForeground(pkg: String?) {
        val p = pkg ?: return
        currentForeground = p
        if (auxActive) {
            setOverlayShown(false)
            return
        }
        if (p == packageName) {
            setOverlayShown(true)
            return
        }
        val settingsGrace = p == "com.android.settings" &&
            SystemClock.elapsedRealtime() < settingsGraceUntilMs
        val allowed = AllowedApps.isMandatory(p) || p in Gate.allowedPackages() || settingsGrace
        if (!allowed && p != lastLoggedBlocked) {
            lastLoggedBlocked = p
            Logx.add("blocked: $p")
        }
        if (allowed) lastLoggedBlocked = null
        setOverlayShown(!allowed)
    }

    /** Safety net in case the accessibility service is off or misses an event. */
    private fun pollUsageOnce() {
        try {
            if (!Checks.hasUsageAccess(this)) return
            val usm = getSystemService(UsageStatsManager::class.java) ?: return
            val now = System.currentTimeMillis()
            // First poll looks further back so an app already open at gate start
            // is dealt with immediately.
            val lookback = if (firstUsagePoll) 300_000L else 10_000L
            firstUsagePoll = false
            val events = usm.queryEvents(now - lookback, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.packageName
                }
            }
            val fg = last
            if (fg != null) handleForeground(fg)
            accrueAppTime(fg)
        } catch (_: Throwable) {
        }
    }

    private fun accrueAppTime(fg: String?) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastPollMs == 0L) 0L else (now - lastPollMs).coerceIn(0L, 5_000L)
        var accrued = false
        if (fg != null && fg == lastPollFg && screenOn && elapsed > 0) {
            for (t in Gate.tasks) {
                t.criteria.withIndex().forEach { (i, c) ->
                    if (c.type == CType.APP_TIME && !Gate.isDone(t.id, i) && fg in c.packages) {
                        Gate.addAppTime(t.id, i, elapsed)
                        accrued = true
                        if (Gate.appTimeMs(t.id, i) >= c.minutes * 60_000L) {
                            markDoneQuiet(t.id, i, "app time")
                        }
                    }
                }
            }
        }
        if (accrued && SystemClock.elapsedRealtime() - lastPersistMs > PERSIST_EVERY_MS) {
            lastPersistMs = SystemClock.elapsedRealtime()
            Gate.persist()
        }
        lastPollMs = now
        lastPollFg = fg
    }

    // ---------- runnables ----------

    private val selfCheck = object : Runnable {
        override fun run() {
            if (!forced) {
                if (!Prefs.enabled || !ScheduleEngine.isInWindow() ||
                    System.currentTimeMillis() < Prefs.releaseUntilMs
                ) {
                    Logx.add("self-check: gate inactive")
                    shutdown()
                    return
                }
                if (Gate.allDone()) {
                    completeAndRelease()
                    return
                }
            }
            updateWarning()
            ScheduleEngine.scheduleHeartbeat(this@GateService)
            handler.postDelayed(this, SELF_CHECK_MS)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            dwellTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val usagePoll = object : Runnable {
        override fun run() {
            pollUsageOnce()
            handler.postDelayed(this, POLL_MS)
        }
    }

    // ---------- dwell ----------

    private fun dwellTick() {
        val now = SystemClock.elapsedRealtime()
        val deltaMs = if (lastTickMs == 0L) 0L
        else (now - lastTickMs).coerceIn(0L, 4L * 60L * 60L * 1000L)
        lastTickMs = now
        val idle = !screenOn && (now - lastMotionMs) > MOTION_GRACE_MS
        if (idle && lastIdle && deltaMs > 0) accrueDwell(deltaMs)
        lastIdle = idle
    }

    private fun accrueDwell(deltaMs: Long) {
        val sec = (deltaMs / 1000L).toInt()
        if (sec <= 0) return
        var changed = false
        for (t in Gate.tasks) {
            t.criteria.withIndex().forEach { (i, c) ->
                if (c.type == CType.DWELL && !Gate.isDone(t.id, i)) {
                    Gate.addDwell(t.id, i, sec)
                    changed = true
                    if (Gate.dwellSec(t.id, i) >= c.minutes * 60) {
                        markDoneQuiet(t.id, i, "dwell")
                    }
                }
            }
        }
        if (changed && SystemClock.elapsedRealtime() - lastPersistMs > PERSIST_EVERY_MS) {
            lastPersistMs = SystemClock.elapsedRealtime()
            Gate.persist()
        }
    }

    private fun onInterruption(reason: String) {
        var had = false
        for (t in Gate.tasks) {
            t.criteria.withIndex().forEach { (i, c) ->
                if (c.type == CType.DWELL && !Gate.isDone(t.id, i) && Gate.dwellSec(t.id, i) > 0) {
                    had = true
                }
            }
        }
        if (had) Logx.add("dwell reset: $reason")
        Gate.resetDwell()
        overlay?.refresh()
    }

    private var screenRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!screenOn) return
                    dwellTick()
                    screenOn = false
                    lastIdle = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (screenOn) return
                    dwellTick() // accrue genuine idle time up to the wake
                    screenOn = true
                    lastIdle = false
                    onInterruption("screen on")
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (!screenOn) {
                        dwellTick()
                        screenOn = true
                    }
                    lastIdle = false
                    onInterruption("unlock")
                }
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenRegistered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(screenReceiver, f)
            screenRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        screenRegistered = false
    }

    // ---------- sensors ----------

    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            registerSignificantMotion() // the trigger sensor disables itself after firing
            lastMotionMs = SystemClock.elapsedRealtime()
            lastIdle = false
            onInterruption("movement")
        }
    }

    private val accelListener = object : SensorEventListener {
        private var lastMag = 0f
        override fun onSensorChanged(e: SensorEvent?) {
            val ev = e ?: return
            val x = ev.values.getOrElse(0) { 0f }
            val y = ev.values.getOrElse(1) { 0f }
            val z = ev.values.getOrElse(2) { 0f }
            val mag = sqrt(x * x + y * y + z * z)
            if (lastMag != 0f && abs(mag - lastMag) > 2.0f) {
                lastMotionMs = SystemClock.elapsedRealtime()
                lastIdle = false
                onInterruption("movement")
            }
            lastMag = mag
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent?) {
            val ev = e ?: return
            if (ev.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val total = ev.values.getOrElse(0) { 0f }.toLong()
            val tMs = ev.timestamp / 1_000_000L
            if (lastTotalSteps >= 0 && total < lastTotalSteps) {
                stepSamples.clear() // reboot (step counter resets with uptime)
            }
            lastTotalSteps = total
            stepSamples.addLast(longArrayOf(tMs, total))
            pruneSamples()
            evaluateSteps()
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun registerSignificantMotion() {
        val sm = sensorManager ?: return
        val sensor = significantMotion ?: return
        try {
            sm.requestTriggerSensor(motionTrigger, sensor)
        } catch (_: Throwable) {
        }
    }

    private fun setupSensors() {
        val sm = sensorManager ?: return
        significantMotion = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (significantMotion != null) {
            registerSignificantMotion()
        } else {
            try {
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_UI)
                }
            } catch (_: Throwable) {
            }
        }
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        registerStepsIfNeeded()
    }

    private fun teardownSensors() {
        val sm = sensorManager ?: return
        try {
            significantMotion?.let { sm.cancelTriggerSensor(motionTrigger, it) }
        } catch (_: Throwable) {
        }
        try {
            sm.unregisterListener(accelListener)
        } catch (_: Throwable) {
        }
        unregisterSteps()
    }

    private fun hasPendingSteps(): Boolean = Gate.tasks.any { t ->
        t.criteria.withIndex().any { (i, c) -> c.type == CType.STEPS && !Gate.isDone(t.id, i) }
    }

    private fun registerStepsIfNeeded() {
        val sm = sensorManager ?: return
        val s = stepSensor ?: return
        if (stepWatcherActive || !hasPendingSteps()) return
        try {
            sm.registerListener(stepListener, s, SensorManager.SENSOR_DELAY_NORMAL)
            stepWatcherActive = true
            stepSamples.clear()
            lastTotalSteps = -1L
        } catch (_: Throwable) {
        }
    }

    private fun unregisterSteps() {
        if (!stepWatcherActive) return
        try {
            sensorManager?.unregisterListener(stepListener)
        } catch (_: Throwable) {
        }
        stepWatcherActive = false
        stepSamples.clear()
    }

    private fun maxStepWindowMs(): Long {
        var w = 5L * 60L * 1000L
        for (t in Gate.tasks) {
            t.criteria.forEach { c ->
                if (c.type == CType.STEPS && c.windowMin > 0) {
                    w = maxOf(w, c.windowMin * 60_000L)
                }
            }
        }
        return w
    }

    private fun pruneSamples() {
        val cutoff = SystemClock.elapsedRealtime() - maxStepWindowMs() - 10_000L
        while (stepSamples.size > 1 && stepSamples.peekFirst()[0] < cutoff) {
            stepSamples.removeFirst()
        }
    }

    private fun stepDeltaFor(c: Criterion): Long {
        val newest = stepSamples.peekLast() ?: return 0L
        val wMs = (c.windowMin.coerceAtLeast(1)) * 60_000L
        var base = newest
        for (s in stepSamples) {
            if (newest[0] - s[0] <= wMs) {
                base = s
                break
            }
        }
        return (newest[1] - base[1]).coerceAtLeast(0L)
    }

    private fun evaluateSteps() {
        val newest = stepSamples.peekLast() ?: return
        for (t in Gate.tasks) {
            t.criteria.withIndex().forEach { (i, c) ->
                if (c.type != CType.STEPS || Gate.isDone(t.id, i)) return@forEach
                val wMs = (c.windowMin.coerceAtLeast(1)) * 60_000L
                var base = newest
                for (s in stepSamples) {
                    if (newest[0] - s[0] <= wMs) {
                        base = s
                        break
                    }
                }
                if (newest[1] - base[1] >= c.steps) markDoneQuiet(t.id, i, "steps")
            }
        }
        if (!hasPendingSteps()) unregisterSteps()
    }

    // ---------- state changes ----------

    private fun markDoneQuiet(taskId: String, idx: Int, reason: String) {
        if (Gate.isDone(taskId, idx)) return
        Gate.markDone(taskId, idx)
        val task = Gate.tasks.firstOrNull { it.id == taskId }
        Logx.add("done: ${task?.name ?: taskId}[$idx] via $reason")
        updateNotification()
        overlay?.refresh()
        handler.post { handleForeground(currentForeground) }
        if (Gate.allDone()) {
            handler.postDelayed({ completeAndRelease() }, 900L)
        }
    }

    private fun completeAndRelease() {
        if (releasing) return
        releasing = true
        Logx.add("all tasks done — releasing")
        Toast.makeText(this, "Morning Gate: all tasks done. Good morning!", Toast.LENGTH_LONG).show()
        ScheduleEngine.cancelHeartbeat(this)
        ScheduleEngine.scheduleNext(this)
        shutdown()
    }

    private fun releaseToday() {
        Prefs.releaseUntilMs = ScheduleEngine.windowEndMs()
        Logx.add("released for today (escape)")
        ScheduleEngine.cancelHeartbeat(this)
        ScheduleEngine.scheduleNext(this)
        shutdown()
    }

    private fun shutdown() {
        Logx.add("gate stopped")
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        unregisterScreenReceiver()
        teardownSensors()
        Gate.persist()
        stopForeground(true)
        stopSelf()
    }

    // ---------- snapshot for the gate screen ----------

    private fun updateWarning() {
        val sb = StringBuilder()
        if (!Checks.isA11yEnabled(this)) {
            sb.append("Accessibility watch is off — apps may leak through.\n")
        }
        val nfcPending = Gate.tasks.any { t ->
            t.criteria.withIndex().any { (i, c) -> c.type == CType.NFC && !Gate.isDone(t.id, i) }
        }
        if (nfcPending && !Checks.isNfcEnabled(this)) {
            sb.append("NFC is off — tag checks can't complete.\n")
        }
        if (hasPendingSteps() && !hasActivityPermission()) {
            sb.append("Physical activity permission is missing — steps checks can't complete.\n")
        }
        warningCache = sb.toString().trim().ifEmpty { null }
    }

    private fun hasActivityPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun snapshot(): GateSnapshot {
        val tasks = Gate.tasks
        val out = ArrayList<TaskView>()
        for (t in tasks) {
            val crits = ArrayList<CritView>()
            t.criteria.withIndex().forEach { (i, c) ->
                val done = Gate.isDone(t.id, i)
                val actions = ArrayList<CritAction>()
                val text = when (c.type) {
                    CType.DWELL -> if (done) "Dwell · done" else {
                        val acc = Gate.dwellSec(t.id, i)
                        "Dwell · ${Format.mmss(acc)} / ${Format.mmss(c.minutes * 60)}" +
                            if (screenOn) " · screen off to run" else " · running"
                    }
                    CType.STEPS -> if (done) "Steps · done" else
                        "Steps · ${stepDeltaFor(c)} / ${c.steps} in ${c.windowMin} min"
                    CType.NFC -> when {
                        done -> "NFC tag · done"
                        c.tagId.isBlank() -> {
                            actions.add(CritAction("Bind tag", null, true))
                            "NFC tag · not bound — tap a tag to bind"
                        }
                        else -> {
                            actions.add(CritAction("Tap tag", null, true))
                            "NFC tag · tap the bound tag"
                        }
                    }
                    CType.QR -> if (done) "QR · done" else "QR · scanning comes in the next build"
                    CType.BARCODE -> if (done) "Barcode · done" else "Barcode · scanning comes in the next build"
                    CType.APP_TIME -> if (done) "App time · done" else {
                        val mins = (Gate.appTimeMs(t.id, i) / 60_000L).toInt()
                        c.packages.take(2).forEach { actions.add(CritAction("Open ${Apps.label(this, it)}", it, false)) }
                        if (c.packages.isEmpty()) "App time · no app chosen"
                        else "App time · $mins / ${c.minutes} min in " +
                            c.packages.joinToString(", ") { Apps.label(this, it) }
                    }
                }
                crits.add(CritView(i, c.type, done, text, actions))
            }
            out.add(TaskView(t.id, t.name, Gate.taskDone(t), crits))
        }
        val doneTasks = tasks.count { Gate.taskDone(it) }
        return GateSnapshot(out, doneTasks, tasks.size, ScheduleEngine.windowEndMs(), warningCache, screenOn)
    }
}
