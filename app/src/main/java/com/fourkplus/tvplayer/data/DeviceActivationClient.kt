package com.fourkplus.tvplayer.data

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Resolves this device's MAC/device-key pair against the self-hosted activation server into a
 *  concrete Provider-login or M3U playlist, mirroring the Stalker/Ministra "activate by MAC"
 *  pattern: the reseller assigns a playlist to a MAC in the admin dashboard, and the device
 *  fetches it here. Deliberately separate from [XtreamProviderClient] since it speaks a different,
 *  first-party API rather than the Xtream Codes provider protocol. */
internal class ActivationPendingException : IllegalArgumentException("No playlist has been assigned to this device yet.")

internal object DeviceActivationClient {
    private const val ACTIVATION_URL = "https://fourk-plus-tv-player.onrender.com/api/activate"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** [input] carries the MAC in [PlaylistInput.username] and the device key in
     *  [PlaylistInput.password]. Returns a resolved M3U_URL or PROVIDER_LOGIN input, or throws
     *  if the activation server hasn't had a playlist assigned to this device yet. */
    suspend fun resolve(input: PlaylistInput, activationUrl: String = ACTIVATION_URL): PlaylistInput {
        val requestBody = JSONObject()
            .put("mac", input.username)
            .put("deviceKey", input.password)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(activationUrl).post(requestBody).build()
        val responseBody = kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    continuation.resumeWith(Result.failure(IllegalArgumentException(
                        "Could not reach the activation service. Check your connection."
                    )))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use {
                            require(it.isSuccessful) { "Could not reach the activation service. Try again shortly." }
                            it.body?.string().orEmpty()
                        }
                    }.recoverCatching {
                        throw IllegalArgumentException("Could not read the activation response. Try again shortly.")
                    }
                    continuation.resumeWith(result)
                }
            })
        }
        val json = runCatching { JSONObject(responseBody) }
            .getOrElse { throw IllegalArgumentException("The activation service returned an unexpected response.") }

        if (json.optString("status") == "pending") {
            throw ActivationPendingException()
        }
        require(json.optString("status") == "assigned") { "The activation service returned an unexpected status." }
        val name = json.optString("name").takeIf(String::isNotBlank) ?: "Activated playlist"
        return when (json.optString("type")) {
            "m3u" -> PlaylistInput(
                name = name,
                kind = PlaylistKind.M3U_URL,
                address = json.optString("url")
            )
            "xtream" -> PlaylistInput(
                name = name,
                kind = PlaylistKind.PROVIDER_LOGIN,
                address = json.optString("server"),
                username = json.optString("username"),
                password = json.optString("password")
            )
            else -> throw IllegalArgumentException("The activation service returned an unsupported playlist type.")
        }
    }
}
