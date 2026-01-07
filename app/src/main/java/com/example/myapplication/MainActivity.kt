package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.captureButton.setOnClickListener {
            val intent = Intent(this, CameraActivity::class.java)
            cameraLauncher.launch(intent)
        }


        binding.uploadButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED


    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(externalMediaDirs.first(), "receipt.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processReceiptImage(photoFile)
                }
            }
        )
    }

    private fun processReceiptImage(photoFile: File) {
        val image = InputImage.fromFilePath(this, android.net.Uri.fromFile(photoFile))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val parsedItems = parseReceipt(visionText.text)

                // Build a formatted string
                val displayText = buildReceiptSummary(parsedItems)

                // Show in TextView
                binding.text.text = displayText
            }
            .addOnFailureListener { e ->
                binding.text.text = "Error: ${e.message}"
            }
    }

    data class ReceiptItem(
        val qty: Int,
        val description: String,
        val price: Double? = null
    )

    private fun parseReceipt(rawText: String): List<ReceiptItem> {
        val lines = rawText.split("\n")
        val items = mutableListOf<ReceiptItem>()
        val totals = mutableListOf<Double>()

        val itemRegex = Regex("""^(\d+)\s+(.+)""")
        val priceRegex = Regex("""^\d+(\.\d+)?$""")

        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            val itemMatch = itemRegex.find(line)
            if (itemMatch != null) {
                val qty = itemMatch.groupValues[1].toInt()
                val desc = itemMatch.groupValues[2]
                items.add(ReceiptItem(qty, desc))
                continue
            }

            val priceMatch = priceRegex.find(line)
            if (priceMatch != null) {
                totals.add(line.toDouble())
            }
        }

        // Map first N totals to first N items
        for (i in items.indices) {
            if (i < totals.size) {
                items[i] = items[i].copy(price = totals[i])
            }
        }

        // Log results
        items.forEach {
            Log.d("ReceiptParser", "Item: ${it.description}, Qty: ${it.qty}, Price: ${it.price}")
        }

        return items
    }

    private fun buildReceiptSummary(items: List<ReceiptItem>): String {
        val builder = StringBuilder()
        builder.append("Receipt Summary:\n\n")
        for (item in items) {
            builder.append("• ${item.description}\n")
            builder.append("   Qty: ${item.qty}, Price: ${item.price}\n\n")
        }
        return builder.toString().trim()
    }

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val photoFile = File(cacheDir, "uploaded_receipt.jpg")
            contentResolver.openInputStream(it)?.use { input ->
                photoFile.outputStream().use { output -> input.copyTo(output) }
            }
            processReceiptImage(photoFile)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val rawText = result.data?.getStringExtra("OCR_TEXT")
            rawText?.let {
                val parsedItems = parseReceipt(it)
                binding.text.text = buildReceiptSummary(parsedItems)
            }
        }
    }


}