package com.gesturemouse

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.gesturemouse.databinding.FragmentAirBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The air-trackpad tab: camera in, mouse intents out.
 *
 * Everything gesture-related lives in [GestureEngine]; this class only feeds it
 * landmarks and forwards what comes back to the Bluetooth mouse.
 */
class AirFragment : Fragment(), GestureEngine.Output {

    private var _b: FragmentAirBinding? = null
    private val b get() = _b!!

    private val mouse: HidMouse? get() = (activity as? MainActivity)?.mouse

    private lateinit var engine: GestureEngine
    private var landmarker: HandLandmarker? = null
    private lateinit var analysisExecutor: ExecutorService
    private var log: GestureLog? = null

    /**
     * Held on to so rotation can update its target.
     *
     * The activity survives a rotation (see configChanges in the manifest, which
     * exists to keep the Bluetooth connection alive), so CameraX is never
     * rebound and would otherwise keep reporting the rotation it was bound at.
     * [analyze] rotates each frame by that number before handing it to
     * MediaPipe, so a stale value means the model sees sideways hands.
     */
    private var analysis: ImageAnalysis? = null

    private var frontCamera = true
    private var frames = 0
    private var fpsMark = 0L
    private var lastResultAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentAirBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        engine = GestureEngine(this)
        log = GestureLog(requireContext().applicationContext)
        engine.logger = { k, d -> log?.log(k, d) }
        analysisExecutor = Executors.newSingleThreadExecutor()

        b.speed.addOnChangeListener { _, value, _ ->
            engine.speed = value
            log?.log("speed", mapOf("value" to value))
        }
        b.flip.setOnClickListener {
            frontCamera = !frontCamera
            engine.release()
            startCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        if ((activity as? MainActivity)?.hasCameraPermission() == true) {
            buildLandmarker()
            startCamera()
        } else {
            b.gestureLabel.text = "no camera"
            b.gestureHint.text = "grant camera access to use air gestures"
        }
    }

    /**
     * Rotation doesn't rebuild this fragment, so CameraX has to be told by hand.
     * The pointer is also released: the hand's frame of reference just moved
     * under it, and carrying the old delta across would fling the cursor.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.display?.rotation?.let { analysis?.targetRotation = it }
        engine.release()
        mouse?.releaseButtons()
    }

    override fun onPause() {
        super.onPause()
        // never leave a mouse button held down because a tab went away
        engine.release()
        mouse?.releaseButtons()
        log?.flush()   // so a session is readable without closing the app
    }

    private fun buildLandmarker() {
        if (landmarker != null) return
        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(Delegate.GPU)
                .build()
            landmarker = HandLandmarker.createFromOptions(
                requireContext(),
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setResultListener { result, _ -> onLandmarks(result) }
                    .setErrorListener { e ->
                        activity?.runOnUiThread {
                            b.gestureHint.text = "tracker error: ${e.message}"
                        }
                    }
                    .build()
            )
        } catch (e: Exception) {
            // GPU delegate isn't available on every device; CPU always is
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .setDelegate(Delegate.CPU)
                    .build()
                landmarker = HandLandmarker.createFromOptions(
                    requireContext(),
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumHands(1)
                        .setResultListener { result, _ -> onLandmarks(result) }
                        .build()
                )
            } catch (e2: Exception) {
                b.gestureHint.text = "could not load the hand model: ${e2.message}"
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val ctx = context ?: return
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(b.preview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
            view?.display?.rotation?.let { analysis.targetRotation = it }
            this.analysis = analysis

            val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                b.gestureHint.text = "camera unavailable: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun analyze(proxy: ImageProxy) {
        val lmk = landmarker
        if (lmk == null) {
            proxy.close()
            return
        }
        try {
            val bitmap = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            proxy.use { bitmap.copyPixelsFromBuffer(it.planes[0].buffer) }

            // rotate upright, and mirror the front camera so moving your hand
            // right moves the cursor right
            val m = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (frontCamera) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)

            lmk.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            proxy.close()
        }
    }

    private fun onLandmarks(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val hands = result.landmarks().map { hand ->
            hand.map { GestureEngine.Landmark(it.x(), it.y()) }
        }
        val points = hands.firstOrNull().orEmpty()

        if (hands.any { it.size >= 21 }) {
            engine.updateHands(hands, now)
            lastResultAt = now
        } else {
            engine.release()
        }

        frames++
        if (now - fpsMark > 1000) {
            val f = frames
            frames = 0
            fpsMark = now
            activity?.runOnUiThread { _b?.fpsLabel?.text = "$f fps" }
            // tracking quality context: a low frame rate or a small hand in
            // frame makes every threshold behave differently, so gesture data
            // can't be read without it
            if (points.size >= 21) {
                val scale = kotlin.math.hypot(
                    points[GestureEngine.WRIST].x - points[GestureEngine.MID_MCP].x,
                    points[GestureEngine.WRIST].y - points[GestureEngine.MID_MCP].y
                )
                log?.log("tracking", mapOf("fps" to f, "handScale" to scale))
            } else {
                log?.log("tracking", mapOf("fps" to f, "hand" to false))
            }
        }
        activity?.runOnUiThread {
            _b?.overlay?.setHands(hands, engine.onPad, engine.sweeping)
        }
    }

    // ---- GestureEngine.Output -------------------------------------------------

    override fun move(dx: Float, dy: Float) { mouse?.move(dx, dy) }
    override fun click() { mouse?.click(HidMouse.BUTTON_LEFT) }
    override fun rightClick() { mouse?.click(HidMouse.BUTTON_RIGHT) }
    override fun buttonDown() { mouse?.buttonDown(HidMouse.BUTTON_LEFT) }
    override fun buttonUp() { mouse?.buttonUp(HidMouse.BUTTON_LEFT) }
    override fun scroll(notches: Int) { mouse?.scroll(notches) }

    override fun state(label: String, hint: String) {
        activity?.runOnUiThread {
            _b?.gestureLabel?.text = label
            _b?.gestureHint?.text = hint
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        engine.release()
        analysis = null
        landmarker?.close()
        landmarker = null
        log?.close()
        log = null
        if (this::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        _b = null
    }
}
