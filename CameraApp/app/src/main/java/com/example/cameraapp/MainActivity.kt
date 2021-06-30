package com.example.cameraapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.concurrent.thread


typealias LumaListener = (luma: Double) -> Unit

public class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    // private var difference: Double? //private このファイル　lateinit 後で初期化
    var difference: Double = 0.0
    var meanRedList: Array<Double> = arrayOf(0.0,0.0,0.0)
    var timeList: Array<LocalDateTime> = arrayOf(
        LocalDateTime.of(2019, 3, 22, 10, 10, 10),
        LocalDateTime.of(2019, 3, 22, 10, 10, 10)
    )
    var heartBeat: ArrayList<Double> = arrayListOf(0.0)
    var cnt: Int = 0
    var timeKeep: Double = 0.0
    var flag: Boolean = false
    var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG,"called onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)



        val btn = findViewById<Button>(R.id.btn)
        btn.setOnClickListener{
            it.isEnabled = false//ボタン押せなくする
            Handler(Looper.getMainLooper()).postDelayed({
                cameraProvider!!.unbindAll()
                it.isEnabled = true
            },5000)
            // Request camera permissions
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG,"finish onCreate")
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
                outputDirectory,
                SimpleDateFormat(FILENAME_FORMAT, Locale.US
                ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(photoFile)
                val msg = "Photo capture succeeded: $savedUri"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, msg)
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

            imageCapture = ImageCapture.Builder()
                    .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { image ->
                            Handler(Looper.getMainLooper()).post {
                                viewFinder.bitmap?.let{ bmp ->
                                    val nPixels = bmp.width * bmp.height
                                    val pixels = IntArray(nPixels)
                                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                                    val reds = pixels.map {p -> (p shr 16) and 0xff }
                                    val meanRed = reds.average()
                                    meanRedList[2] = meanRedList[1]
                                    meanRedList[1] = meanRedList[0]
                                    meanRedList[0] = meanRed//巡回配列
                                    if (meanRedList[1] > meanRedList[0] && meanRedList[1] < meanRedList[2]){
                                        val dateAndtime: LocalDateTime = LocalDateTime.now()
                                        timeList[1] = timeList[0]
                                        timeList[0] = dateAndtime
                                        difference = ChronoUnit.MILLIS.between(timeList[1], timeList[0]).toDouble()
                                        heartBeat.add(1.0/difference*60.0*1000)
                                        val meanHeartBeat = heartBeat.sum() / heartBeat.size
                                        valueView.text = "%.1f".format(meanHeartBeat)
                                    }
                                    Log.d(TAG, "Average RED: $meanRed")
                                }
                            }
                            image.close()
                        })
                    }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider!!.unbindAll()

                // Bind use cases to camera
                cameraProvider!!.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}