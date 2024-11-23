package com.example.colorcv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.colorcv.ui.theme.ColorCVTheme
import com.google.common.util.concurrent.ListenableFuture
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class MainActivity : ComponentActivity() {
    lateinit var previewView: PreviewView
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var binaryDataText by mutableStateOf("Waiting for transmission...")
    private var isReceiving by mutableStateOf(false) // State to control transmission start
    private val binaryData = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initDebug()

        // Request camera permissions if not granted
        if (allPermissionsGranted()) {
            setupCameraProvider()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        setContent {
            ColorCVTheme {
                MainContent(binaryDataText, isReceiving) { startReceiving() }
            }
        }
    }

    private fun setupCameraProvider() {
        // Initialize the CameraProvider
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        // Configure the preview use case
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Configure image analysis
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Keeps only the latest frame
            .build()
            .also {
                it.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                    if (isReceiving) { // Only process frames if receiving
                        val mat = imageProxyToMat(image)
                        if (!mat.empty()) {
                            detectLedColor(mat)
                            mat.release()
                        }
                    }
                    image.close()
                }
            }

        try {
            // Ensure any previous use cases are unbound before binding new ones
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
            )
        } catch (exc: Exception) {
            Log.e("CameraX", "Use case binding failed", exc)
        }
    }

    private fun startReceiving() {
        isReceiving = true
        binaryDataText = "Receiving Binary Data..."
    }

    private fun detectLedColor(mat: Mat) {
        if (mat.empty()) {
            Log.e("DetectLedColor", "Empty frame received, skipping processing")
            return
        }

        // Convert the image to HSV color space
        val hsvMat = Mat()
        Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_RGB2HSV)

        // Define HSV ranges for each color based on Arduino's transmitted colors
        val colorRanges = mapOf(
            "000" to Pair(Scalar(0.0, 120.0, 70.0), Scalar(10.0, 255.0, 255.0)),  // Red
            "001" to Pair(Scalar(35.0, 100.0, 100.0), Scalar(85.0, 255.0, 255.0)), // Green
            "010" to Pair(Scalar(100.0, 150.0, 50.0), Scalar(120.0, 255.0, 255.0)), // Blue
            "011" to Pair(Scalar(80.0, 50.0, 180.0), Scalar(95.0, 150.0, 255.0)),  // Cyan
            "100" to Pair(Scalar(140.0, 100.0, 100.0), Scalar(160.0, 255.0, 255.0)), // Magenta
            "101" to Pair(Scalar(20.0, 100.0, 100.0), Scalar(30.0, 255.0, 255.0)),  // Yellow
            "110" to Pair(Scalar(128.0, 100.0, 50.0), Scalar(148.0, 255.0, 255.0)), // Purple
            "111" to Pair(Scalar(10.0, 100.0, 100.0), Scalar(20.0, 255.0, 255.0))   // Orange
        )


        val whiteRange = Pair(Scalar(0.0, 0.0, 200.0), Scalar(180.0, 50.0, 255.0)) // White color

        // Check for the white color as the end signal
        val whiteMask = Mat()
        Core.inRange(hsvMat, whiteRange.first, whiteRange.second, whiteMask)
        val whiteArea = Core.countNonZero(whiteMask)

        if (whiteArea > 0) { // White color detected, end of transmission
            binaryDataText = "Transmission Ended\nBinary Data: ${binaryData.toString()}"
            binaryData.clear()
            isReceiving = false // Stop receiving after end signal
            whiteMask.release()
            hsvMat.release()
            return
        }
        whiteMask.release()

        // Process colors for data collection
        colorRanges.forEach { (binary, range) ->
            val mask = Mat()
            Core.inRange(hsvMat, range.first, range.second, mask)

            // Calculate the area of each mask and determine if it matches the transmitted symbol
            val area = Core.countNonZero(mask)
            if (area > 0) { // If there's a matching area in this color range
                binaryData.append(binary) // Append binary value for detected color
                binaryDataText = "Binary Data: ${binaryData.toString()}" // Update live binary data display
            }
            mask.release()
        }

        hsvMat.release()
    }

    private fun imageProxyToMat(image: ImageProxy): Mat {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        if (ySize == 0 || uSize == 0 || vSize == 0) {
            return Mat() // Return an empty Mat if any buffer is empty
        }

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)
        val rgbMat = Mat()

        if (yuvMat.empty()) {
            return Mat() // Return an empty Mat if the YUV Mat is empty
        }

        Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)
        yuvMat.release() // Release yuvMat as it's no longer needed

        return rgbMat
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the camera when the activity is destroyed
        cameraProviderFuture.get().unbindAll()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

@Composable
fun MainContent(
    binaryDataText: String,
    isReceiving: Boolean,
    startReceiving: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AndroidView(
                factory = {
                    PreviewView(context).apply {
                        (context as? MainActivity)?.previewView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )

            Button(
                onClick = { startReceiving() },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Start Transmission")
            }

            Text(
                text = binaryDataText,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
