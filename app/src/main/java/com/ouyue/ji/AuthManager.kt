package com.ouyue.ji

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthState(
    val userId: String = "",
    val token: String = "",
    val plan: String = "free",
    val proExpiresAt: String? = null,
    val usedToday: Int = 0,
    val dailyQuota: Int = 3,
    val requiresRecovery: Boolean = false
) {
    val isPro: Boolean
        get() = plan.equals("pro", ignoreCase = true)
}

data class ProHint(
    val requiresPro: Boolean = false,
    val reason: String = "",
    val freeSaveLimit: Int = 3
)

class AuthManager(
    context: Context,
    parseEndpointUrl: String
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ji_auth", Context.MODE_PRIVATE)
    private val securePrefs = appContext.getSharedPreferences("ji_auth_secure", Context.MODE_PRIVATE)
    private val baseUrl = parseEndpointUrl.substringBeforeLast("/", missingDelimiterValue = parseEndpointUrl)
    private val lock = Any()

    fun cachedState(): AuthState = AuthState(
        userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
        token = getStoredToken(),
        plan = prefs.getString(KEY_PLAN, "free").orEmpty().ifBlank { "free" },
        proExpiresAt = prefs.getString(KEY_PRO_EXPIRES_AT, null),
        usedToday = prefs.getInt(KEY_USED_TODAY, 0),
        dailyQuota = prefs.getInt(KEY_DAILY_QUOTA, 3)
    )

    suspend fun ensureSession(forceRefresh: Boolean = false): AuthState? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            synchronized(lock) {
                cachedState().takeIf { it.token.isNotBlank() }
            }?.let { return@withContext it }
        }

        val existingToken = cachedState().token
        val response = postJson(
            path = "/auth/anonymous",
            body = JSONObject(),
            token = existingToken.takeIf { it.isNotBlank() }
        ) ?: return@withContext null
        if (!response.isSuccessfulHttp()) return@withContext null

        saveAuthState(response)
        cachedState()
    }

    suspend fun fetchMe(): AuthState? = withContext(Dispatchers.IO) {
        val token = cachedState().token.ifBlank { ensureSession()?.token.orEmpty() }
        if (token.isBlank()) return@withContext null

        val response = getJson(path = "/me", token = token) ?: return@withContext cachedState()
        if (response.isUnauthorizedHttp()) {
            val cached = cachedState()
            if (cached.hasActiveProCache()) {
                return@withContext cached.copy(requiresRecovery = true)
            }
            clearAuthState()
            return@withContext ensureSession(forceRefresh = true)
        }
        if (!response.isSuccessfulHttp()) return@withContext cachedState()
        saveAuthState(response)
        cachedState()
    }

    suspend fun redeemCode(code: String): Result<AuthState> = withContext(Dispatchers.IO) {
        val normalizedCode = normalizeActivationCode(code)
        if (normalizedCode.isBlank()) {
            return@withContext Result.failure(IllegalStateException("请输入激活码"))
        }

        val token = cachedState().token.ifBlank { ensureSession()?.token.orEmpty() }
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("账号初始化失败，请稍后重试"))
        }

        var response = postJson(
            path = "/redeem-code",
            body = JSONObject().put("code", normalizedCode),
            token = token
        ) ?: return@withContext Result.failure(IllegalStateException("兑换失败，请检查激活码"))

        if (response.isUnauthorizedHttp()) {
            clearAuthState()
            val refreshedToken = ensureSession(forceRefresh = true)?.token.orEmpty()
            if (refreshedToken.isNotBlank()) {
                response = postJson(
                    path = "/redeem-code",
                    body = JSONObject().put("code", normalizedCode),
                    token = refreshedToken
                ) ?: response
            }
        }

        val responseCode = response.optString("code")
        if (responseCode == "CODE_USED_BY_CURRENT_USER") {
            saveAuthState(response)
            return@withContext Result.success(fetchMe() ?: cachedState())
        }
        if (responseCode.isNotBlank()) {
            response.put("message", redeemMessageFor(responseCode, response.optString("message")))
        }

        if (!response.isSuccessfulHttp() || response.optBoolean("ok", true) == false) {
            return@withContext Result.failure(
                IllegalStateException(response.optString("message").ifBlank { "兑换失败，请检查激活码" })
            )
        }

        saveAuthState(response)
        val refreshedState = fetchMe() ?: cachedState()
        if (!refreshedState.isPro) {
            return@withContext Result.failure(IllegalStateException("兑换成功，但状态暂未刷新，请稍后重试"))
        }
        Result.success(refreshedState)
    }

    fun bearerToken(): String = cachedState().token

    fun updateFromUsage(usageJson: JSONObject?) {
        if (usageJson == null) return
        prefs.edit()
            .putString(KEY_PLAN, usageJson.optString("plan", cachedState().plan))
            .putString(KEY_PRO_EXPIRES_AT, usageJson.optNullableString("proExpiresAt") ?: usageJson.optNullableString("pro_expires_at"))
            .putInt(KEY_USED_TODAY, usageJson.optInt("usedToday", usageJson.optInt("used_today", cachedState().usedToday)))
            .putInt(KEY_DAILY_QUOTA, usageJson.optInt("dailyQuota", usageJson.optInt("daily_quota", cachedState().dailyQuota)))
            .apply()
    }

    private fun saveAuthState(json: JSONObject) {
        val userId = json.optString("userId", json.optString("user_id", cachedState().userId))
        val token = json.optString("token", cachedState().token)
        val usage = json.optJSONObject("usage")

        putStoredToken(token)
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .remove(KEY_TOKEN)
            .putString(KEY_PLAN, json.optString("plan", usage?.optString("plan") ?: cachedState().plan))
            .putString(KEY_PRO_EXPIRES_AT, json.optNullableString("proExpiresAt") ?: json.optNullableString("pro_expires_at"))
            .putInt(KEY_USED_TODAY, json.optInt("usedToday", json.optInt("used_today", usage?.optInt("usedToday", cachedState().usedToday) ?: cachedState().usedToday)))
            .putInt(KEY_DAILY_QUOTA, json.optInt("dailyQuota", json.optInt("daily_quota", usage?.optInt("dailyQuota", cachedState().dailyQuota) ?: cachedState().dailyQuota)))
            .apply()
    }

    private fun clearAuthState() {
        clearStoredToken()
        prefs.edit()
            .remove(KEY_USER_ID)
            .putString(KEY_PLAN, "free")
            .remove(KEY_PRO_EXPIRES_AT)
            .putInt(KEY_USED_TODAY, 0)
            .putInt(KEY_DAILY_QUOTA, 3)
            .apply()
    }

    private fun getJson(path: String, token: String): JSONObject? {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return readJsonResponse(connection)
    }

    private fun postJson(path: String, body: JSONObject, token: String?): JSONObject? {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return readJsonResponse(connection)
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject? {
        return try {
            val statusCode = connection.responseCode
            if (BuildConfig.DEBUG) {
                Log.d(NetworkLogTag, "[AUTH_HTTP] url=${connection.url} status=$statusCode")
            }
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: return null
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text).apply {
                put(KEY_HTTP_CODE, statusCode)
                if (statusCode !in 200..299 && !has("ok")) put("ok", false)
                if (statusCode !in 200..299 && !has("message") && has("detail")) {
                    put("message", opt("detail")?.toString().orEmpty())
                }
            }
        } catch (exception: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(NetworkLogTag, "[AUTH_HTTP_ERROR] url=${connection.url} error=${exception::class.java.simpleName}")
            }
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeActivationCode(code: String): String =
        code.trim()
            .uppercase()
            .replace(" ", "")
            .replace("－", "-")
            .replace("—", "-")
            .replace("–", "-")

    private fun getStoredToken(): String {
        securePrefs.getString(KEY_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { encryptedToken ->
                decryptToken(encryptedToken)?.takeIf { it.isNotBlank() }?.let { return it }
            }

        val legacyToken = prefs.getString(KEY_TOKEN, "").orEmpty()
        if (legacyToken.isNotBlank()) {
            putStoredToken(legacyToken)
        }
        return legacyToken
    }

    private fun putStoredToken(token: String) {
        if (token.isBlank()) {
            clearStoredToken()
            return
        }
        val encryptedToken = encryptToken(token) ?: return
        securePrefs.edit().putString(KEY_TOKEN, encryptedToken).apply()
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun clearStoredToken() {
        securePrefs.edit().remove(KEY_TOKEN).apply()
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun encryptToken(token: String): String? =
        runCatching {
            val cipher = Cipher.getInstance(AUTH_TOKEN_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + ciphertext
            Base64.encodeToString(payload, Base64.NO_WRAP)
        }.getOrNull()

    private fun decryptToken(encryptedToken: String): String? =
        runCatching {
            val payload = Base64.decode(encryptedToken, Base64.NO_WRAP)
            require(payload.size > AUTH_TOKEN_IV_BYTES)
            val iv = payload.copyOfRange(0, AUTH_TOKEN_IV_BYTES)
            val ciphertext = payload.copyOfRange(AUTH_TOKEN_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(AUTH_TOKEN_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(AUTH_TOKEN_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AUTH_TOKEN_KEYSTORE).apply { load(null) }
        val existingKey = (keyStore.getEntry(AUTH_TOKEN_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AUTH_TOKEN_KEYSTORE)
        val keySpec = KeyGenParameterSpec.Builder(
            AUTH_TOKEN_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun JSONObject.isSuccessfulHttp(): Boolean =
        optInt(KEY_HTTP_CODE, 200) in 200..299

    private fun JSONObject.isUnauthorizedHttp(): Boolean =
        optInt(KEY_HTTP_CODE, 200) == HttpURLConnection.HTTP_UNAUTHORIZED

    private fun redeemMessageFor(code: String, message: String): String =
        when (code) {
            "CODE_NOT_FOUND" -> "激活码不存在，请检查后重试"
            "CODE_EXPIRED" -> "激活码已过期"
            "CODE_ALREADY_USED" -> "激活码已被其他匿名账号使用，请保留激活码和用户 ID 联系开发者人工恢复。"
            "CODE_USED_BY_CURRENT_USER" -> "Pro 已开通"
            else -> message.ifBlank { "兑换失败，请检查激活码后重试" }
        }

    private fun AuthState.hasActiveProCache(): Boolean {
        if (!isPro) return false
        val expiresAt = proExpiresAt?.takeIf { it.isNotBlank() } ?: return true
        return runCatching {
            OffsetDateTime.parse(expiresAt).toInstant().isAfter(Instant.now())
        }.getOrDefault(true)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private companion object {
        const val NetworkLogTag = "JiNetwork"
        const val KEY_USER_ID = "user_id"
        const val KEY_TOKEN = "token"
        const val KEY_PLAN = "plan"
        const val KEY_PRO_EXPIRES_AT = "pro_expires_at"
        const val KEY_USED_TODAY = "used_today"
        const val KEY_DAILY_QUOTA = "daily_quota"
        const val KEY_HTTP_CODE = "__http_code"
        const val AUTH_TOKEN_KEYSTORE = "AndroidKeyStore"
        const val AUTH_TOKEN_ALIAS = "ji_auth_token_key"
        const val AUTH_TOKEN_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AUTH_TOKEN_IV_BYTES = 12
        const val AUTH_TOKEN_TAG_BITS = 128
    }
}
