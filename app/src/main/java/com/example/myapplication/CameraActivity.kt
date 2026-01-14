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

    // Capture by lines
    // Capture by lines, sorted top-to-bottom (ignore left/right)
    // Capture by lines, sorted top-to-bottom (ignore left/right), filter out phone numbers
    // Capture by lines, sorted top-to-bottom, filter out phone numbers and single letters
    // Capture by lines, sorted top-to-bottom, filter out phone numbers, single letters, and unwanted keywords
    fun captureLines(visionText: com.google.mlkit.vision.text.Text): String {
        val lines = visionText.textBlocks.flatMap { it.lines }

        // Sort by vertical position (top of bounding box)
        val sortedLines = lines.sortedBy { it.boundingBox?.top ?: 0 }

        // Regex for phone numbers
        val phoneRegex = Regex("""(\+?\d[\d\s\-\(\)]{6,15}\d)""")

        // Keywords to filter out (lowercase for matching)
        val unwantedKeywords = listOf(
            "subtotal", "total", "tax", "+ tax",
            "debit card", "credit card", "change", "items"
        )

        // Filter out lines
        val filteredLines = sortedLines.filter { line ->
            val text = line.text.trim()
            val lower = text.lowercase()

            val isPhone = phoneRegex.containsMatchIn(text)
            val isSingleLetter = text.length == 1 && text[0].isLetter()
            val hasUnwantedKeyword = unwantedKeywords.any { lower.contains(it) }

            !(isPhone || isSingleLetter || hasUnwantedKeyword)
        }

        return filteredLines.joinToString("\n") { line ->
            line.text
        }
    }

}