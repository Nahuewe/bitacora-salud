package ar.nahuel.bitacora.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed class ApiError(val code: String, val retryable: Boolean) : Exception(code) {
    class NotConfigured : ApiError("not_configured", false)
    class InvalidCredentials : ApiError("invalid_credentials", false)
    class Unauthorized : ApiError("unauthorized", false)
    class RateLimited : ApiError("rate_limited", true)
    class Server(status: Int) : ApiError("server_$status", true)
    class Rejected(status: Int) : ApiError("rejected_$status", false)
    class Network : ApiError("network", true)
}

@Serializable
data class AuthSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("email") val email: String,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("user") val user: TokenUser,
)

@Serializable
private data class TokenUser(@SerialName("email") val email: String? = null)

@Serializable
private data class PasswordGrant(val email: String, val password: String)

@Serializable
private data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)

class SupabaseApi(
    baseUrl: String,
    private val apiKey: String,
    private val json: Json,
    private val http: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trimEnd('/')
    val isConfigured: Boolean = base.startsWith("https://") && apiKey.isNotBlank()

    suspend fun signIn(email: String, password: String): AuthSession {
        val body = json.encodeToString(PasswordGrant.serializer(), PasswordGrant(email.trim(), password))
        return token("password", body, email.trim())
    }

    suspend fun refresh(session: AuthSession): AuthSession {
        val body = json.encodeToString(RefreshGrant.serializer(), RefreshGrant(session.refreshToken))
        return token("refresh_token", body, session.email)
    }

    suspend fun signOut(session: AuthSession) {
        if (!isConfigured) return
        val request = Request.Builder()
            .url("$base/auth/v1/logout")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { execute(request) { } }
    }

    suspend fun upsert(table: String, onConflict: String, bodyJson: String, session: AuthSession) {
        val url = "$base/rest/v1/$table".toHttpUrl().newBuilder()
            .addQueryParameter("on_conflict", onConflict)
            .build()
        val request = authorized(session)
            .url(url)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(bodyJson.toRequestBody(JSON))
            .build()
        execute(request) { }
    }

    suspend fun insert(table: String, bodyJson: String, session: AuthSession) {
        val request = authorized(session)
            .url("$base/rest/v1/$table")
            .header("Prefer", "return=minimal")
            .post(bodyJson.toRequestBody(JSON))
            .build()
        execute(request) { }
    }

    suspend fun select(table: String, columns: String, session: AuthSession): String {
        val url = "$base/rest/v1/$table".toHttpUrl().newBuilder()
            .addQueryParameter("select", columns)
            .build()
        val request = authorized(session).url(url).get().build()
        return execute(request) { it.body?.string().orEmpty() }
    }

    suspend fun delete(table: String, filters: Map<String, String>, session: AuthSession) {
        val builder = "$base/rest/v1/$table".toHttpUrl().newBuilder()
        require(filters.isNotEmpty()) { "delete sin filtros" }
        filters.forEach { (column, value) -> builder.addQueryParameter(column, "eq.$value") }
        val request = authorized(session)
            .url(builder.build())
            .header("Prefer", "return=minimal")
            .delete()
            .build()
        execute(request) { }
    }

    private suspend fun token(grant: String, body: String, fallbackEmail: String): AuthSession {
        if (!isConfigured) throw ApiError.NotConfigured()
        val url = "$base/auth/v1/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", grant)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .post(body.toRequestBody(JSON))
            .build()
        val parsed = execute(request, authEndpoint = true) { response ->
            json.decodeFromString(TokenResponse.serializer(), response.body?.string().orEmpty())
        }
        return AuthSession(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            expiresAt = System.currentTimeMillis() / 1000 + parsed.expiresIn,
            email = parsed.user.email ?: fallbackEmail,
        )
    }

    private fun authorized(session: AuthSession): Request.Builder {
        if (!isConfigured) throw ApiError.NotConfigured()
        return Request.Builder()
            .header("apikey", apiKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
    }

    private suspend fun <T> execute(
        request: Request,
        authEndpoint: Boolean = false,
        onSuccess: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network()
        }
        response.use {
            when {
                it.isSuccessful -> onSuccess(it)
                authEndpoint && (it.code == 400 || it.code == 401) -> throw ApiError.InvalidCredentials()
                it.code == 401 || it.code == 403 -> throw ApiError.Unauthorized()
                it.code == 429 -> throw ApiError.RateLimited()
                it.code >= 500 -> throw ApiError.Server(it.code)
                else -> throw ApiError.Rejected(it.code)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
