package com.pranav.morninggate

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.InputType
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Offline QR / barcode scanner. Two modes:
 *  - bind: return the scanned payload to the task editor.
 *  - use:  satisfy a gate check; binds the code on the first scan if it wasn't bound.
 *
 * Frames are decoded on-device with ZXing. Nothing leaves the phone.
 */
class ScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_USE = "use"
        const val MODE_BIND = "bind"
        const val EXTRA_KIND = "kind"
        const val KIND_QR = "qr"
        const val KIND_BARCODE = "barcode"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_INDEX = "index"
        const val EXTRA_PAYLOAD = "payload"
        private const val REQ_CAM = 31
        private const val TRY_EVERY_MS = 110L
        private const val FINISH_DELAY_MS = 650L
    }

    private var mode = MODE_USE
    private var kind = KIND_QR
    private var taskId = ""
    private var index = -1

    @Volatile
    private var handled = false
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var binding = false
    private var torchOn = false
    private var permGoesToSettings = false
    private var lastTryMs = 0L
    private var lastWrong = ""
    private var lastWrongMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var reader: MultiFormatReader? = null

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var permButton: Button
    private lateinit var torchButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Gate.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_USE
        kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_QR
        taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        if (mode == MODE_USE) {
            when (criterion()?.type) {
                CType.QR -> kind = KIND_QR
                CType.BARCODE -> kind = KIND_BARCODE
                else -> {}
            }
        }

        setContentView(buildUi())

        if (!hasCamera()) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
    }

    override fun onResume() {
        super.onResume()
        GateService.onAuxResume()
        if (hasCamera()) {
            permButton.visibility = View.GONE
            bindCamera()
        }
    }

    override fun onPause() {
        GateService.onAuxPause()
        super.onPause()
    }

    override fun onDestroy() {
        GateService.onAuxDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(26))
            setBackgroundColor(0xFF0B0F14.toInt())
        }

        col.addView(TextView(this).apply {
            text = if (mode == MODE_BIND) "Bind a code" else "Scan to verify"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })

        if (mode == MODE_USE) {
            Gate.tasks.firstOrNull { it.id == taskId }?.let { t ->
                col.addView(TextView(this).apply {
                    text = "Task: ${t.name}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(0xFF8A94A6.toInt())
                })
            }
        }

        val label = if (kind == KIND_QR) "QR code" else "barcode"
        val hint = if (mode == MODE_BIND) {
            "Point the camera at the $label you want to bind to this check."
        } else {
            val c = criterion()
            when {
                c == null -> "Point the camera at the $label."
                c.payload.isBlank() -> "No code bound yet — the first scan binds it."
                c.label.isNotBlank() -> "Scan \"${c.label}\" to verify this check."
                else -> "Scan the bound $label to verify this check."
            }
        }
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hlp.topMargin = dp(8)
        col.addView(TextView(this).apply {
            text = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8A94A6.toInt())
            setLineSpacing(0f, 1.2f)
        }, hlp)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val frame = FrameLayout(this)
        frame.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val box = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), 0xFFFFC46B.toInt())
            }
        }
        val boxSize = dp(240)
        frame.addView(box, FrameLayout.LayoutParams(boxSize, boxSize, Gravity.CENTER))
        val flp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        flp.topMargin = dp(14)
        col.addView(frame, flp)

        statusView = TextView(this).apply {
            text = "Starting camera…"
            setTextColor(0xFFFFC46B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(14)
        col.addView(statusView, slp)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        permButton = Button(this).apply {
            text = "Grant camera"
            visibility = View.GONE
            setOnClickListener {
                if (permGoesToSettings) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Throwable) {
                    }
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
                }
            }
        }
        torchButton = Button(this).apply {
            text = "Flash on"
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        buttons.addView(permButton)
        buttons.addView(torchButton)
        buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        val blp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        blp.topMargin = dp(10)
        col.addView(buttons, blp)

        return col
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    // ---------------- camera ----------------

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun bindCamera() {
        if (binding || analysis != null) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            setStatus("This phone has no camera.")
            return
        }
        binding = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            binding = false
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val res = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val an = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(res)
                    .build()
                an.setAnalyzer(executor) { proxy -> analyzeFrame(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    an
                )
                analysis = an
                setStatus("Point at the code…")
                torchButton.visibility =
                    if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
            } catch (t: Throwable) {
                Logx.add("camera failed: ${t.javaClass.simpleName}")
                setStatus("Camera unavailable: ${t.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val c = camera ?: return
        torchOn = !torchOn
        try {
            c.cameraControl.enableTorch(torchOn)
        } catch (_: Throwable) {
        }
        torchButton.text = if (torchOn) "Flash off" else "Flash on"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAM) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            permButton.visibility = View.GONE
            bindCamera()
            return
        }
        permGoesToSettings = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        setStatus(
            if (permGoesToSettings) "Camera is blocked for this app."
            else "Camera permission is needed to scan codes."
        )
        permButton.text = if (permGoesToSettings) "Open app settings" else "Try again"
        permButton.visibility = View.VISIBLE
        Logx.add("camera permission missing")
    }

    // ---------------- decoding ----------------

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (handled || now - lastTryMs < TRY_EVERY_MS) return
            lastTryMs = now

            val w = proxy.width
            val h = proxy.height
            val plane = proxy.planes.getOrNull(0) ?: return
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val data = ByteArray(w * h)
            buf.rewind()
            if (rowStride == w) {
                buf.get(data, 0, minOf(data.size, buf.remaining()))
            } else {
                val row = ByteArray(rowStride)
                var out = 0
                for (y in 0 until h) {
                    val start = y * rowStride
                    if (start >= buf.limit()) break
                    buf.position(start)
                    val n = minOf(rowStride, buf.remaining())
                    if (n <= 0) break
                    buf.get(row, 0, n)
                    System.arraycopy(row, 0, data, out, w)
                    out += w
                }
            }

            val (rotated, rw, rh) = rotate(data, w, h, proxy.imageInfo.rotationDegrees)
            val text = try {
                val src = PlanarYUVLuminanceSource(rotated, rw, rh, 0, 0, rw, rh, false)
                reader()?.decode(BinaryBitmap(HybridBinarizer(src)))?.text
            } catch (_: ReaderException) {
                null
            } catch (_: Throwable) {
                null
            }
            if (text != null) runOnUiThread { handleDecoded(text) }
        } catch (_: Throwable) {
        } finally {
            proxy.close()
        }
    }

    private fun reader(): MultiFormatReader? {
        var r = reader
        if (r == null) {
            val formats = if (kind == KIND_QR) {
                listOf(BarcodeFormat.QR_CODE)
            } else {
                listOf(
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                    BarcodeFormat.CODE_93, BarcodeFormat.ITF,
                    BarcodeFormat.CODABAR
                )
            }
            val hints = HashMap<DecodeHintType, Any>()
            hints[DecodeHintType.POSSIBLE_FORMATS] = formats
            hints[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
            r = MultiFormatReader().apply { setHints(hints) }
            reader = r
        }
        return r
    }

    /** Rotate the luminance buffer clockwise by 90 / 180 / 270 degrees. */
    private fun rotate(src: ByteArray, w: Int, h: Int, deg: Int): Triple<ByteArray, Int, Int> {
        return when (((deg % 360) + 360) % 360) {
            90 -> {
                val dst = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        dst[x * h + (h - 1 - y)] = src[y * w + x]
                    }
                }
                Triple(dst, h, w)
            }
            180 -> {
                val dst = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
                    }
                }
                Triple(dst, w, h)
            }
            270 -> {
                val dst = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        dst[(w - 1 - x) * h + y] = src[y * w + x]
                    }
                }
                Triple(dst, h, w)
            }
            else -> Triple(src, w, h)
        }
    }

    // ---------------- results ----------------

    private fun criterion(): Criterion? {
        val t = Gate.tasks.firstOrNull { it.id == taskId } ?: return null
        return t.criteria.getOrNull(index)
    }

    private fun handleDecoded(raw: String) {
        if (handled) return
        val text = raw.trim()
        if (text.isEmpty()) return

        if (mode == MODE_BIND) {
            handled = true
            Logx.add("code bound: ${preview(text)}")
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, text))
            successFeedback("Code captured")
            handler.postDelayed({ finish() }, FINISH_DELAY_MS)
            return
        }

        val ti = Gate.tasks.indexOfFirst { it.id == taskId }
        val task = if (ti >= 0) Gate.tasks.getOrNull(ti) else null
        val crit = task?.criteria?.getOrNull(index)
        if (task == null || crit == null) {
            setStatus("This check no longer exists.")
            return
        }

        when {
            crit.payload.isBlank() -> {
                // First scan binds the code (ask what it is) and satisfies today's check.
                handled = true
                setStatus("Code captured — name it")
                askCodeName { name ->
                    val newCrits = task.criteria.toMutableList()
                    newCrits[index] = crit.copy(payload = text, label = name)
                    val newTasks = Gate.tasks.toMutableList()
                    newTasks[ti] = task.copy(criteria = newCrits)
                    Gate.saveTasks(newTasks)
                    Gate.markDone(taskId, index)
                    Logx.add(
                        if (name.isBlank()) "code bound + done: ${preview(text)}"
                        else "code bound \"$name\" + done: ${preview(text)}"
                    )
                    successFeedback(
                        if (name.isBlank()) "Code bound — check complete"
                        else "\"$name\" bound — check complete"
                    )
                    GateService.requestRefresh()
                    handler.postDelayed({ finish() }, FINISH_DELAY_MS)
                }
            }
            crit.payload.trim() == text -> {
                Gate.markDone(taskId, index)
                handled = true
                Logx.add("code matched: ${preview(text)}")
                successFeedback("Code matched — check complete")
                GateService.requestRefresh()
                handler.postDelayed({ finish() }, FINISH_DELAY_MS)
            }
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (text != lastWrong || now - lastWrongMs > 2_500L) {
                    lastWrong = text
                    lastWrongMs = now
                    setStatus("Not this code. Try the bound one.")
                    Logx.add("code mismatch: ${preview(text)}")
                }
            }
        }
    }

    private fun successFeedback(msg: String) {
        setStatus("✓  $msg")
        try {
            val v = getSystemService(Vibrator::class.java)
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Throwable) {
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * The first scan binds the code; ask what it is so the gate can name the
     * demand later ("scan the meds box"). Skipping binds it unnamed.
     */
    private fun askCodeName(onDone: (String) -> Unit) {
        val edit = EditText(this).apply {
            hint = "e.g. Meds box, fridge door…"
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                edit,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        var done = false
        fun go(name: String) {
            if (done) return
            done = true
            onDone(name)
        }
        AlertDialog.Builder(this)
            .setTitle("Name this code")
            .setMessage("The gate shows this name, so you know which code it's demanding.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> go(edit.text.toString().trim()) }
            .setNegativeButton("Skip") { _, _ -> go("") }
            .setOnCancelListener { go("") }
            .show()
    }

    private fun preview(s: String): String = if (s.length <= 40) s else s.take(40) + "…"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
