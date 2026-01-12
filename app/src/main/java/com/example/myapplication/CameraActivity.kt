package com.example.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        val captureBtn = findViewById<Button>(R.id.captureButton)

        startCamera()

        captureBtn.setOnClickListener {
            takePhoto()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val photoFile = File(externalMediaDirs.first(), "receipt.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    setResult(RESULT_CANCELED)
                    finish()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val image = InputImage.fromFilePath(this@CameraActivity, Uri.fromFile(photoFile))
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val capturedText = captureLines(visionText)   // try lines
                            // val capturedText = captureBlocks(visionText) // try blocks
                            //val capturedText = captureElements(visionText) // try words
                            //val capturedText = captureWithBounds(visionText) // debug layout

                            val intent = Intent().apply {
                                putExtra("OCR_TEXT", capturedText)
                            }
                            setResult(RESULT_OK, intent)
                            finish()
                        }
                        .addOnFailureListener {
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                }
            }
        )
    }

    // Capture full text (default)
    fun captureFullText(visionText: com.google.mlkit.vision.text.Text): String {
        return visionText.text
    }

    // Capture by blocks
    fun captureBlocks(visionText: com.google.mlkit.vision.text.Text): String {
        return visionText.textBlocks.joinToString("\n") { block ->
            "BLOCK: ${block.text}"
        }
    }

    // Capture by lines
    // Capture by lines, sorted top-to-bottom (ignore left/right)
    fun captureLines(visionText: com.google.mlkit.vision.text.Text): String {
        val lines = visionText.textBlocks.flatMap { it.lines }

        // Sort by vertical position (top of bounding box)
        val sortedLines = lines.sortedBy { it.boundingBox?.top ?: 0 }

        return sortedLines.joinToString("\n") { line ->
            line.text
        }
    }

    // Capture by words/elements
    fun captureElements(visionText: com.google.mlkit.vision.text.Text): String {
        return visionText.textBlocks.flatMap { it.lines }
            .flatMap { it.elements }
            .joinToString(" ") { element ->
                element.text
            }
    }

    // Capture with bounding boxes (for debugging layout)
    fun captureWithBounds(visionText: com.google.mlkit.vision.text.Text): String {
        val sb = StringBuilder()
        for (block in visionText.textBlocks) {
            sb.append("BLOCK: ${block.text} at ${block.boundingBox}\n")
            for (line in block.lines) {
                sb.append("  LINE: ${line.text} at ${line.boundingBox}\n")
                for (element in line.elements) {
                    sb.append("    WORD: ${element.text} at ${element.boundingBox}\n")
                }
            }
        }
        return sb.toString()
    }
}