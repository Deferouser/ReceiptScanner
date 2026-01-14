package com.example.myapplication

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ReceiptApiService {
    @POST("receipts")
    suspend fun sendReceipt(@Body receipt: ReceiptSummaryDto): Response<ReceiptResponseDto>
}
