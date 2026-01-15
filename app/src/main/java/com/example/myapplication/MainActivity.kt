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

            val dto = summary.toDto()
            Log.d("ReceiptDTO", "Sending: $dto")

            lifecycleScope.launch {
                try {
                    val response = RetrofitClient.instance.sendReceipt(dto)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body != null) {
                            // ✅ Collect and display response
                            val storeStatus = if (body.store_exists) "Store found" else "Store not found"
                            val itemStatuses = body.items_exist.mapIndexed { index, exists ->
                                "Item ${index + 1}: ${if (exists) "Found" else "Not found"}"
                            }.joinToString("\n")

                            binding.text.text = """
                        $storeStatus
                        $itemStatuses
                        Status: ${body.status}
                    """.trimIndent()
                        }
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
        val upper = rawText.uppercase()

        return when {
            upper.contains("ARDENNE") -> parseArdenneReceipt(rawText)
            upper.contains("LOSHUSAN") -> parseLoshusanReceipt(rawText)
            else -> parseGenericReceipt(rawText)
        }
    }


    private fun parseArdenneReceipt(rawText: String): ReceiptSummary {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val items = mutableListOf<ReceiptItem>()
        val addressLines = mutableListOf<String>()

        var storeName: String? = null
        var capturingAddress = false

        val skipKeywords = listOf(
            "TOTAL","SUBTOTAL","BALANCE","SALES","TAX","USER","DATE","RECEIPT",
            "CHANGE", "THANK YOU", "RETURN", "REFUND"
        )

        // Regex to match lines like "1 Pepsi 591ml 169.65"
        val itemLineRegex = Regex("""^(\d+)\s+(.+?)\s+(\d+(?:\.\d{1,2})?)$""")

        for (line in lines) {
            val upper = line.uppercase()

            // Skip junk lines
            if (skipKeywords.any { upper.contains(it) }) continue

            // Capture store name (first line before junk)
            if (storeName == null) {
                storeName = line
                capturingAddress = true
                continue
            }

            // Capture address lines until first item
            if (capturingAddress) {
                if (itemLineRegex.matches(line)) {
                    capturingAddress = false
                } else {
                    addressLines.add(line)
                    continue
                }
            }

            // Match actual item lines
            val match = itemLineRegex.find(line)
            if (match != null) {
                val qty = match.groupValues[1].toInt()
                val description = match.groupValues[2].trim()
                val price = match.groupValues[3].toDoubleOrNull() ?: 0.0
                items.add(ReceiptItem(qty, description, price))
            }
        }

        // Validate store name
        val validKeywords = listOf("PHARMACY","STORE","SUPER","MART","GIFT","CENTRE")
        val storeNameMissing = storeName == null || validKeywords.none { storeName!!.uppercase().contains(it) }

        return ReceiptSummary(
            storeName = storeName,
            address = addressLines.joinToString(", "),
            items = items,
            storeNameMissing = storeNameMissing
        )
    }


    private fun parseLoshusanReceipt(rawText: String): ReceiptSummary {
        val lines = rawText
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val items = mutableListOf<ReceiptItem>()
        val storeName = "Loshusan Supermarket"
        val location = "New Kingston"

        val skipKeywords = listOf(
            "TOTAL", "SUBTOTAL", "SALES", "CARD", "NCB",
            "ITEM COUNT", "RECEIPT", "THANK YOU",
            "NO EXCHANGE", "DONATE", "BREAST CANCER",
            "INV#", "TRS#", "TEL#", "DATE", "TIME"
        )

        val categoryKeywords = listOf(
            "PRODUCE", "BAKED GOODS", "GROCERY", "MEAT", "DAIRY"
        )

        // Regex to match: description + optional $ + price + optional TGCT
        val itemLineRegex = Regex("""^(.+?)\s+\$?(\d+(?:\.\d{1,2}))\s*(?:TG[A-Z]+)?$""")

        for (line in lines) {
            val upper = line.uppercase()

            // Skip metadata / junk
            if (skipKeywords.any { upper.contains(it) }) continue
            if (upper == storeName.uppercase()) continue
            if (categoryKeywords.any { upper == it }) continue

            // Match line containing item + price
            val match = itemLineRegex.find(line) ?: continue

            var description = match.groupValues[1].trim()
            val price = match.groupValues[2].toDouble()

            // Remove leading category if present
            for (category in categoryKeywords) {
                if (description.uppercase().startsWith(category)) {
                    description = description.substring(category.length).trim()
                }
            }

            // Safety check: must be at least two words
            if (description.split(" ").size < 2) continue

            items.add(ReceiptItem(1, description, price))
        }

        return ReceiptSummary(storeName, location, items, false)
    }




    private fun parseGenericReceipt(rawText: String): ReceiptSummary {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val storeName = lines.firstOrNull()
        val items = mutableListOf<ReceiptItem>()

        val itemRegex = Regex("""^(\d+)\s+(.+)""")
        for (line in lines) {
            val match = itemRegex.find(line)
            if (match != null) {
                val qty = match.groupValues[1].toInt()
                val desc = match.groupValues[2]
                items.add(ReceiptItem(qty = qty, description = desc))
            }
        }

        return ReceiptSummary(storeName, null, items, storeName == null)
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

    private fun isLikelyProductLine(line: String): Boolean {
        val text = line.trim()
        val upper = text.uppercase()

        // Must contain a $ price
        if (!Regex("""\$\s*\d+\.\d{2}""").containsMatchIn(text)) return false

        // Must contain letters
        if (!text.any { it.isLetter() }) return false

        // Reject sentence-like lines
        val rejectKeywords = listOf(
            "TOTAL", "SUBTOTAL", "GCT", "SALES",
            "CARD", "NCB", "SCOTIA",
            "ITEM COUNT", "RECEIPT",
            "DONATE", "THANK YOU",
            "NO EXCHANGE", "CASH REFUND"
        )
        if (rejectKeywords.any { upper.contains(it) }) return false

        // Reject categories or store name
        val categoryKeywords = listOf(
            "PRODUCE", "BAKED GOODS", "SUPERMARKET"
        )
        if (categoryKeywords.any { upper == it }) return false

        // Product names usually have multiple words
        if (text.split(" ").size < 2) return false


        return true
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