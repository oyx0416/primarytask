package com.ouyue.ji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

class AiImageTaskParser(
    private val endpointUrl: String,
    private val authManager: AuthManager? = null
) : ImageTaskParser {
    private companion object {
        const val NetworkLogTag = "JiNetwork"
        const val NormalUploadMaxEdge = 1280
        const val ComplexUploadMaxEdge = 1440
        const val NormalUploadJpegQuality = 82
        const val ComplexUploadJpegQuality = 85
        const val NormalReadTimeoutMs = 12_000
        const val ComplexReadTimeoutMs = 20_000
    }

    private val resultCache = object : LinkedHashMap<String, List<ParsedTaskDraft>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ParsedTaskDraft>>?): Boolean =
            size > 6
    }

    var lastUsage: AuthState? = null
        private set
    var lastProHint: ProHint? = null
        private set
    var lastServerMessage: String = ""
        private set
    var lastResponseReceived: Boolean = false
        private set

    override suspend fun parse(context: Context, imageUri: Uri): List<ParsedTaskDraft> {
        if (endpointUrl.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            lastUsage = authManager?.ensureSession()
            lastProHint = null
            lastServerMessage = ""
            lastResponseReceived = false

            val uploadImage = prepareUploadImage(context, imageUri) ?: return@withContext emptyList()
            resultCache[uploadImage.cacheKey]?.let { cachedDrafts ->
                return@withContext cachedDrafts
            }

            val firstResult = postImage(uploadImage, authManager?.bearerToken())
            val finalResult = if (firstResult.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && authManager != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(NetworkLogTag, "[AI_HTTP_RETRY] status=401 error=Unauthorized")
                }
                lastUsage = authManager.ensureSession(forceRefresh = true)
                postImage(uploadImage, authManager.bearerToken())
            } else {
                firstResult
            }
            finalResult.drafts.also { drafts ->
                if (drafts.isNotEmpty()) {
                    resultCache[uploadImage.cacheKey] = drafts
                }
            }
        }
    }

    private fun postImage(uploadImage: UploadImage, token: String?): ParseRequestResult {
        val boundary = "JiImageBoundary${System.currentTimeMillis()}"
        val requestStartedAt = System.currentTimeMillis()
        var uploadMs = 0L
        var waitMs = 0L
        var statusCode = -1
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = if (uploadImage.complex) ComplexReadTimeoutMs else NormalReadTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { bearerToken ->
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
        }

        return try {
            val uploadStartedAt = System.currentTimeMillis()
            writeMultipartImage(uploadImage, connection, boundary)
            uploadMs = System.currentTimeMillis() - uploadStartedAt

            val waitStartedAt = System.currentTimeMillis()
            val responseCode = connection.responseCode.also { statusCode = it }
            waitMs = System.currentTimeMillis() - waitStartedAt
            if (responseCode !in 200..299) {
                parseServerError(connection)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        NetworkLogTag,
                        "[AI_HTTP] status=$responseCode uploadMs=$uploadMs waitMs=$waitMs " +
                            "parseMs=0 totalMs=${System.currentTimeMillis() - requestStartedAt} tasks=0"
                    )
                }
                return ParseRequestResult(responseCode, emptyList())
            }

            val parseStartedAt = System.currentTimeMillis()
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
            val responseJson = JSONObject(responseText)
            lastResponseReceived = true
            lastServerMessage = responseJson.optString("message").trim()
            authManager?.updateFromUsage(responseJson.optJSONObject("usage"))
            lastUsage = authManager?.cachedState()
            lastProHint = responseJson.optJSONObject("proHint")?.let { hintJson ->
                ProHint(
                    requiresPro = hintJson.optBoolean("requiresPro", false),
                    reason = hintJson.optString("reason").trim(),
                    freeSaveLimit = hintJson.optInt("freeSaveLimit", 3).coerceAtLeast(0)
                )
            }

            val drafts = parseResponse(responseJson)
            if (BuildConfig.DEBUG) {
                Log.d(
                    NetworkLogTag,
                    "[AI_HTTP] status=$responseCode uploadMs=$uploadMs waitMs=$waitMs " +
                        "parseMs=${System.currentTimeMillis() - parseStartedAt} " +
                        "totalMs=${System.currentTimeMillis() - requestStartedAt} tasks=${drafts.size}"
                )
            }
            ParseRequestResult(responseCode, drafts)
        } catch (exception: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    NetworkLogTag,
                    "[AI_HTTP_ERROR] status=$statusCode uploadMs=$uploadMs waitMs=$waitMs " +
                        "totalMs=${System.currentTimeMillis() - requestStartedAt} error=${exception::class.java.simpleName}"
                )
            }
            ParseRequestResult(-1, emptyList())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseServerError(connection: HttpURLConnection) {
        lastResponseReceived = true
        val responseText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (responseText.isBlank()) return
        runCatching {
            val json = JSONObject(responseText)
            authManager?.updateFromUsage(json.optJSONObject("usage"))
            lastUsage = authManager?.cachedState()
            lastServerMessage = json.optString("message").trim()
        }
    }

    private fun writeMultipartImage(
        uploadImage: UploadImage,
        connection: HttpURLConnection,
        boundary: String
    ) {
        val fileName = "shared-image.jpg"

        connection.outputStream.use { outputStream ->
            outputStream.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            outputStream.write(
                "Content-Disposition: form-data; name=\"image\"; filename=\"$fileName\"\r\n"
                    .toByteArray(Charsets.UTF_8)
            )
            outputStream.write("Content-Type: ${uploadImage.contentType}\r\n\r\n".toByteArray(Charsets.UTF_8))

            outputStream.write(uploadImage.bytes)

            outputStream.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun parseResponse(responseJson: JSONObject): List<ParsedTaskDraft> {
        val tasks = responseJson.optJSONArray("tasks") ?: return emptyList()
        val drafts = mutableListOf<ParsedTaskDraft>()

        for (index in 0 until tasks.length()) {
            val taskJson = tasks.optJSONObject(index) ?: continue
            val title = taskJson.optFirstString("title", "t").trim()
            if (title.isBlank()) continue

            val time = taskJson.optFirstString("time", "ddl").trim()
            val rawCategory = normalizeShortCategory(taskJson.optFirstString("category", "cat").trim())
            val rawSubType = normalizeShortType(taskJson.optFirstString("subType", "type").trim()).ifBlank {
                if (rawCategory !in listOf("学习", "工作", "生活", "其他")) rawCategory else ""
            }
            val course = taskJson.optFirstString("course", "contextValue").trim()
            val shortCourse = taskJson.optFirstString("course").trim()
            val contextValue = taskJson.optString("contextValue").trim()
                .ifBlank { shortCourse }
                .ifBlank { course }
            val note = taskJson.optFirstString("note", "details").trim()
            val rawText = taskJson.optString("rawText").trim()
            val subType = normalizeDraftSubType(
                category = rawCategory,
                subType = rawSubType,
                text = listOf(title, rawCategory, rawSubType, course, contextValue, time, note, rawText)
                    .joinToString(" ")
            )
            val importance = normalizeDraftImportance(
                importance = normalizeShortImportance(taskJson.optFirstString("importance", "imp").trim()),
                text = listOf(title, rawCategory, subType, contextValue, time, note, rawText)
                    .joinToString(" ")
            )
            val category = normalizeDraftCategory(rawCategory, subType)
            val contextLabel = taskJson.optString("contextLabel").trim().ifBlank {
                defaultContextLabel(category, subType).takeIf { contextValue.isNotBlank() || course.isNotBlank() }.orEmpty()
            }

            drafts += ParsedTaskDraft(
                title = title,
                category = category,
                subType = subType,
                importance = importance,
                contextLabel = contextLabel,
                contextValue = contextValue,
                status = normalizeDraftStatus(taskJson.optFirstString("status", "state").trim()),
                course = course,
                time = time,
                dueTime = time,
                location = taskJson.optFirstString("location", "loc").trim(),
                platform = taskJson.optFirstString("platform", "src").trim(),
                materials = taskJson.optString("materials").trim(),
                note = note,
                rawText = rawText
            )
        }

        if (BuildConfig.DEBUG) {
            Log.d("JiTaskFlow", "[AI_PARSED_TASKS] count=${drafts.size}")
        }
        return drafts
    }

    private fun prepareUploadImage(context: Context, imageUri: Uri): UploadImage? {
        val resolver = context.contentResolver
        val readStartedAt = System.currentTimeMillis()
        val originalBytes = resolver.openInputStream(imageUri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: return null
        val readMs = System.currentTimeMillis() - readStartedAt

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, boundsOptions)

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    NetworkLogTag,
                    "[AI_IMAGE_PREP] readMs=$readMs compressMs=0"
                )
            }
            return UploadImage(
                bytes = originalBytes,
                contentType = context.contentResolver.getType(imageUri) ?: "image/jpeg",
                cacheKey = originalBytes.sha256(),
                originalBytes = originalBytes.size,
                targetEdge = 0,
                jpegQuality = 0,
                complex = true,
                readMs = readMs,
                compressMs = 0L
            )
        }

        val complex = isComplexOrLongImage(width, height)
        val targetEdge = if (complex) ComplexUploadMaxEdge else NormalUploadMaxEdge
        val jpegQuality = if (complex) ComplexUploadJpegQuality else NormalUploadJpegQuality

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(width, height, targetEdge)
        }
        val compressStartedAt = System.currentTimeMillis()
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions) ?: return null

        val scaledBitmap = bitmap.scaleDownToMaxEdge(targetEdge)
        val bytes = ByteArrayOutputStream().use { output ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            output.toByteArray()
        }
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        bitmap.recycle()
        val compressMs = System.currentTimeMillis() - compressStartedAt

        if (BuildConfig.DEBUG) {
            Log.d(
                NetworkLogTag,
                "[AI_IMAGE_PREP] readMs=$readMs compressMs=$compressMs"
            )
        }

        return UploadImage(
            bytes = bytes,
            contentType = "image/jpeg",
            cacheKey = bytes.sha256(),
            originalBytes = originalBytes.size,
            originalWidth = width,
            originalHeight = height,
            targetEdge = targetEdge,
            jpegQuality = jpegQuality,
            complex = complex,
            readMs = readMs,
            compressMs = compressMs
        )
    }

    private fun isComplexOrLongImage(width: Int, height: Int): Boolean {
        val longEdge = max(width, height)
        val shortEdge = max(1, min(width, height))
        val aspectRatio = longEdge.toFloat() / shortEdge.toFloat()
        return aspectRatio >= 2.2f || longEdge >= 2200
    }

    private fun Bitmap.scaleDownToMaxEdge(targetEdge: Int): Bitmap {
        val longEdge = max(width, height)
        if (longEdge <= targetEdge) return this

        val scale = targetEdge.toFloat() / longEdge.toFloat()
        val targetWidth = max(1, (width * scale).toInt())
        val targetHeight = max(1, (height * scale).toInt())
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2

        while (halfWidth / inSampleSize >= maxDimension && halfHeight / inSampleSize >= maxDimension) {
            inSampleSize *= 2
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun JSONObject.optFirstString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun normalizeShortCategory(value: String): String =
        when (value.lowercase()) {
            "study" -> "学习"
            "work" -> "工作"
            "life", "daily" -> "生活"
            "other" -> "其他"
            else -> value
        }

    private fun normalizeShortType(value: String): String =
        when (value.lowercase()) {
            "homework" -> "作业"
            "exam" -> "考试"
            "interview" -> "面试"
            "payment" -> "缴费"
            "meeting" -> "会议"
            "reminder" -> "提醒"
            "shopping", "project", "other" -> "提醒"
            else -> value
        }

    private fun normalizeShortImportance(value: String): String =
        when (value.lowercase()) {
            "high" -> "高"
            "normal", "low" -> "普通"
            else -> value
        }

    private fun defaultContextLabel(category: String, subType: String): String =
        when (category) {
            "学习" -> "课程"
            "工作" -> if (subType == "面试") "公司/岗位" else "项目"
            "生活" -> when (subType) {
                "缴费" -> "缴费项目"
                else -> "事项"
            }
            else -> ""
        }

    private data class ParseRequestResult(
        val statusCode: Int,
        val drafts: List<ParsedTaskDraft>
    )

    private data class UploadImage(
        val bytes: ByteArray,
        val contentType: String,
        val cacheKey: String,
        val originalBytes: Int = 0,
        val originalWidth: Int = 0,
        val originalHeight: Int = 0,
        val targetEdge: Int = NormalUploadMaxEdge,
        val jpegQuality: Int = NormalUploadJpegQuality,
        val complex: Boolean = false,
        val readMs: Long = 0L,
        val compressMs: Long = 0L
    )

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
