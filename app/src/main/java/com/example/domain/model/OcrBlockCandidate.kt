package com.example.domain.model

import android.graphics.Rect

data class OcrLineItem(
    val text: String,
    val boundingBox: Rect?,
    val confidence: Float
)

data class OcrBlockCandidate(
    val text: String,
    val boundingBox: Rect,
    val lines: List<OcrLineItem>
)
