package com.ouyue.ji

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiTaskParser(
    private val endpointUrl: String
) : TaskParser {
    override suspend fun parse(rawText: String): ParsedTaskDraft? {
        if (endpointUrl.isBlank() || rawText.isBlank()) return null

        return withContext(Dispatchers.IO) {
            val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val requestJson = JSONObject()
                    .put("rawText", rawText)
                    .toString()

                connection.outputStream.use { outputStream ->
                    outputStream.write(requestJson.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) return@withContext null

                val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText()
                }

                parseResponse(responseText, rawText)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseResponse(responseText: String, fallbackRawText: String): ParsedTaskDraft? {
        val json = JSONObject(responseText)
        val title = json.optString("title").trim()
        if (title.isBlank()) return null

        val time = json.optString("time").trim()
        val rawCategory = normalizeTextCategory(json.optString("category").trim())
        val rawSubType = json.optString("subType").trim().ifBlank {
            if (rawCategory !in listOf("学习", "工作", "生活", "其他")) rawCategory else ""
        }
        val course = json.optString("course").trim()
        val contextValue = json.optString("contextValue").trim().ifBlank { course }
        val note = json.optString("note").trim()
        val rawText = json.optString("rawText").trim().ifBlank { fallbackRawText }
        val subType = normalizeDraftSubType(
            category = rawCategory,
            subType = rawSubType,
            text = listOf(title, rawCategory, rawSubType, course, contextValue, time, note, rawText)
                .joinToString(" ")
        )
        val importance = normalizeDraftImportance(
            importance = json.optString("importance").trim(),
            text = listOf(title, rawCategory, subType, contextValue, time, note, rawText)
                .joinToString(" ")
        )
        val category = normalizeDraftCategory(rawCategory, subType)
        val contextLabel = json.optString("contextLabel").trim().ifBlank {
            defaultContextLabel(category, subType).takeIf { contextValue.isNotBlank() || course.isNotBlank() }.orEmpty()
        }

        return ParsedTaskDraft(
            title = title,
            category = category,
            subType = subType,
            importance = importance,
            contextLabel = contextLabel,
            contextValue = contextValue,
            status = normalizeDraftStatus(json.optString("status").trim()),
            time = time,
            location = json.optString("location").trim(),
            platform = json.optString("platform").trim(),
            materials = json.optString("materials").trim(),
            course = course,
            dueTime = time,
            note = note,
            rawText = rawText
        )
    }

    private fun normalizeTextCategory(value: String): String =
        when (value.lowercase()) {
            "study" -> "学习"
            "work" -> "工作"
            "life", "daily" -> "生活"
            "other" -> "其他"
            "日常" -> "生活"
            else -> value
        }

    private fun defaultContextLabel(category: String, subType: String): String =
        when (category) {
            "学习" -> "课程"
            "工作" -> if (subType == "面试") "公司/岗位" else "项目"
            "生活" -> if (subType == "缴费") "缴费项目" else "事项"
            else -> ""
        }
}
