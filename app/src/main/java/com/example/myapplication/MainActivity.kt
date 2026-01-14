package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
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

        binding.sendButton.setOnClickListener {
            val rawText = binding.rawText.text.toString()
            val summary = parseReceipt(rawText)

            if (summary.storeNameMissing) {
                binding.text.text = "Store name not detected. Please retake or upload another photo."
            } else {
                binding.text.text = buildReceiptSummary(summary)
            }

            val dto = summary.toDto()
            Log.d("ReceiptDTO", "Sending: $dto")

            // API call
            lifecycleScope.launch {
                try {
                    val response = RetrofitClient.instance.sendReceipt(dto)
                    if (response.isSuccessful) {
                        val body = response.body()
                        Log.d("ReceiptDTO", "Server response: $body")
                        Toast.makeText(this@MainActivity, "Sent successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e("ReceiptDTO", "Error: ${response.code()} ${response.message()}")
                    }
                } catch (e: Exception) {
                    Log.e("ReceiptDTO", "Exception: ${e.message}")
                }
            }
        }
    }

    fun ReceiptItem.toDto(): ReceiptItemDto {
        return ReceiptItemDto(
            quantity = this.qty,
            description = this.description,
            price = this.price
        )
    }

    fun ReceiptSummary.toDto(): ReceiptSummaryDto {
        return ReceiptSummaryDto(
            storeName = this.storeName,
            address = this.address,
            items = this.items.map { it.toDto() }
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


    private fun parseReceipt(rawText: String): ReceiptSummary {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val items = mutableListOf<ReceiptItem>()
        val candidatePrices = mutableListOf<Double>()

        var storeName: String? = null
        val addressLines = mutableListOf<String>()

        val itemRegex = Regex("""^(\d+)\s+(.+)""")
        val priceRegex = Regex("""^\$?\d{1,3}(,\d{3})*(\.\d{1,2})?\s*[A-Z]?$""")
        val weightRegex = Regex("""([\d.]+)\s*kg\s*@\s*([\d.,]+)\/kg""")
        val skipKeywords = listOf("TOTAL","SUBTOTAL","BALANCE","SALES","TAX","USER","DATE","RECEIPT")

        var capturingAddress = false

        for (line in lines) {
            // Capture store name (first line before keywords)
            if (storeName == null && skipKeywords.none { line.uppercase().contains(it) }) {
                storeName = line
                capturingAddress = true
                continue
            }

            // Capture address lines until keywords or items
            if (capturingAddress) {
                if (skipKeywords.any { line.uppercase().contains(it) } || itemRegex.find(line) != null) {
                    capturingAddress = false
                } else {
                    addressLines.add(line)
                    continue
                }
            }

            // Weighted item
            val weightMatch = weightRegex.find(line)
            if (weightMatch != null) {
                items.add(ReceiptItem(qty = 1, description = "Weighted Item"))
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

        // Fallback: try to guess store name later
        if (storeName == null || storeName.contains(Regex("""\d""")) ||
            storeName.contains("ROAD", true) || storeName.contains("SHOP", true)) {
            val candidate = lines.firstOrNull {
                it.uppercase().contains("PHARMACY") ||
                        it.uppercase().contains("SUPERMARKET") ||
                        it.uppercase().contains("STORE") ||
                        it.uppercase().contains("CENTRE") ||
                        it.uppercase().contains("MART") ||
                        it.uppercase().contains("MARKET")
            }
            if (candidate != null) {
                storeName = candidate
            }
        }

        // ✅ Validation: mark missing if store name doesn’t contain expected keywords
        val validKeywords = listOf("MARKET", "SUPER", "STORE", "PHARMACY", "CENTRE", "SHOP", "MART", "GROCERY")
        val storeNameMissing = storeName == null ||
                validKeywords.none { keyword -> storeName!!.uppercase().contains(keyword) }

        return ReceiptSummary(storeName, addressLines.joinToString(", "), items, storeNameMissing)
    }

    private fun buildReceiptSummary(summary: ReceiptSummary): String {
        val builder = StringBuilder()
        builder.append("Store: ${summary.storeName ?: "Unknown"}\n")
        builder.append("Address: ${summary.address ?: "Unknown"}\n\n")
        builder.append("Receipt Summary:\n\n")
        for (item in summary.items) {
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