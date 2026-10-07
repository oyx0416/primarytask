package com.ouyue.ji

import android.content.Context
import android.net.Uri

interface ImageTaskParser {
    suspend fun parse(context: Context, imageUri: Uri): List<ParsedTaskDraft>
}
