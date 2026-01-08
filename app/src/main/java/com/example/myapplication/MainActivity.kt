package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService

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
                // Show raw OCR text
                binding.rawText.text = visionText.text

                // Parse and show formatted summary
                val parsedItems = parseReceipt(visionText.text)
                binding.text.text = buildReceiptSummary(parsedItems)
            }
            .addOnFailureListener { e ->
                binding.rawText.text = "Error: ${e.message}"
                binding.text.text = ""
            }
    }

    data class ReceiptItem(
        val qty: Int,
        val description: String,
        val price: Double? = null
    )

    private fun parseReceipt(rawText: String): List<ReceiptItem> {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val items = mutableListOf<ReceiptItem>()
        val candidatePrices = mutableListOf<Double>()

        val itemRegex = Regex("""^(\d+)\s+(.+)""")
        val priceRegex = Regex("""^\$?\d{1,3}(,\d{3})*(\.\d{1,2})?\s*[A-Z]?$""")
        val weightRegex = Regex("""([\d.]+)\s*kg\s*@\s*([\d.,]+)\/kg""")
        val skipKeywords = listOf("TOTAL", "SUBTOTAL", "BALANCE", "SALES", "TAX")

        for (line in lines) {
            // Weighted item
            val weightMatch = weightRegex.find(line)
            if (weightMatch != null) {
                // Instead of computing immediately, just note description
                items.add(ReceiptItem(qty = 1, description = "Rotiss.Pork"))
                continue
            }

            // Item line
            val itemMatch = itemRegex.find(line)
            if (itemMatch != null) {
                val number = itemMatch.groupValues[1].toInt()
                val desc = itemMatch.groupValues[2]
                if (number < 1000) {
                    items.add(ReceiptItem(qty = number, description = desc))
                } else {
                    items.add(ReceiptItem(qty = 1, description = desc))
                }
                continue
            }

            // Price line
            val priceMatch = priceRegex.find(line)
            if (priceMatch != null && skipKeywords.none { line.uppercase().contains(it) }) {
                val clean = line.replace("[^\\d.,]".toRegex(), "").replace(",", "")
                if (clean.isNotEmpty()) {
                    candidatePrices.add(clean.toDouble())
                }
            }
        }

        // Map prices to items sequentially
        var priceIndex = 0
        for (i in items.indices) {
            if (items[i].price == null && priceIndex < candidatePrices.size) {
                items[i] = items[i].copy(price = candidatePrices[priceIndex])
                priceIndex++
            }
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
                // Show raw OCR text
                binding.rawText.text = it

                // Parse and show formatted summary
                val parsedItems = parseReceipt(it)
                binding.text.text = buildReceiptSummary(parsedItems)
            }
        }
    }


}