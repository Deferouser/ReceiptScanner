package com.example.myapplication

data class ReceiptItemDto(
    val quantity: Int,
    val description: String,
    val price: Double?
)

data class ReceiptSummaryDto(
    val storeName: String?,
    val address: String?,
    val items: List<ReceiptItemDto>
)

data class ReceiptItem(
    val qty: Int,
    val description: String,
    val price: Double? = null
)

data class ReceiptSummary(
    val storeName: String?,
    val address: String?,
    val items: List<ReceiptItem>,
    val storeNameMissing: Boolean = false
)

data class ApiResponse(
    val status: String,
    val received: ReceiptSummaryDto
)

data class ReceiptResponseDto(
    val store_exists: Boolean,
    val items_exist: List<Boolean>,
    val status: String
)
