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

    //Does not accurately capture receipts lines if too close to the camera
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
                            // Just grab the raw text
                            val mergedText = extractVisualLines(visionText)

                            val cleanedText = mergedText
                                .split("\n")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .filterNot { isReceiptNoise(it) }
                                .joinToString("\n")

                            val intent = Intent().apply {
                                putExtra("OCR_TEXT", cleanedText)
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

    private fun extractVisualLines(visionText: com.google.mlkit.vision.text.Text): String {
        data class LineBox(
            val text: String,
            val top: Int,
            val bottom: Int
        )

        val lines = mutableListOf<LineBox>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                lines.add(
                    LineBox(
                        text = line.text,
                        top = box.top,
                        bottom = box.bottom
                    )
                )
            }
        }

        // Sort top-to-bottom
        lines.sortBy { it.top }

        val mergedLines = mutableListOf<String>()
        var currentGroup = mutableListOf<LineBox>()

        fun flushGroup() {
            if (currentGroup.isNotEmpty()) {
                mergedLines.add(
                    currentGroup
                        .joinToString(" ") { it.text }
                        .replace(Regex("""\s{2,}"""), " ")
                        .trim()
                )
                currentGroup.clear()
            }
        }

        for (line in lines) {
            if (currentGroup.isEmpty()) {
                currentGroup.add(line)
                continue
            }

            val last = currentGroup.last()

            // If lines overlap vertically → same row
            val overlap =
                minOf(last.bottom, line.bottom) -
                        maxOf(last.top, line.top)

            if (overlap > 0) {
                currentGroup.add(line)
            } else {
                flushGroup()
                currentGroup.add(line)
            }
        }

        flushGroup()

        return mergedLines.joinToString("\n")
    }
    private fun isReceiptNoise(line: String): Boolean {
        val text = line.uppercase().trim()

        // 1️⃣ Empty or too short
        if (text.length < 3) return true

        // 2️⃣ Phone numbers (926-4811, Tel# 926-4811)
        if (Regex("""\b(TEL|TEL#|PHONE)?\s*\d{3}[-\s]?\d{4}\b""").containsMatchIn(text))
            return true

        // 3️⃣ Card / payment lines
        val paymentKeywords = listOf(
            "CARD", "DEBIT", "CREDIT", "VISA", "MASTERCARD",
            "NCB", "SCOTIA", "BANK"
        )
        if (paymentKeywords.any { text.contains(it) }) return true

        // 4️⃣ Totals / tax
        val totalsKeywords = listOf(
            "TOTAL", "SUBTOTAL", "TAX", "SALES", "BALANCE"
        )
        if (totalsKeywords.any { text.contains(it) }) return true

        // 5️⃣ Metadata
        val metaKeywords = listOf(
            "RECEIPT", "INV#", "TRS#", "ITEM COUNT",
            "DATE", "TIME", "CASHIER"
        )
        if (metaKeywords.any { text.contains(it) }) return true

        // 6️⃣ Numeric-only or currency-only lines
        if (Regex("""^\$?\d+(\.\d{2})?$""").matches(text)) return true

        return false
    }


}