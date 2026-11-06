/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One bounded request, with native credential injection and no redirects. */
internal class JsAppHttp(
    private val checkUrl: (String) -> HttpUrl,
    private val secret: (String) -> String?,
    client: OkHttpClient = CLIENT,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun request(options: JsonObject): String {
        val url = options.text("url")
        require(url.startsWith("https://", ignoreCase = true)) { "HTTPS is required" }
        val destination = checkUrl(url)
        val timeout = options["timeoutMs"]?.let { (it as? JsonPrimitive)?.longOrNull
            ?: error("Invalid HTTP timeout") } ?: 60_000L
        require(timeout in 1..60_000L) { "Invalid HTTP timeout" }
        val method = options.text("method").ifBlank { "GET" }.uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported HTTP method" }
        val body = options.text("body")
        require(body.toByteArray().size <= 1024 * 1024) { "Request body is too large" }
        val extra = options["headers"] as? JsonObject ?: JsonObject(emptyMap())
        val contentType = extra.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.jsonPrimitive?.content ?: "application/json"
        val credential = options.text("credential")
        val headers = JsVoiceHeaders.build(credential,
            if (credential.isBlank()) null else secret(credential),
            options.text("authHeader"), options.text("authPrefix"),
            JsonObject(extra.filterKeys { !it.equals("Content-Type", true) }).toString())
        val request = Request.Builder().url(destination)
            .method(method, if (method == "GET") null else body.toRequestBody(contentType.toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return suspendCancellableCoroutine { continuation ->
            // Credentials may involve storage/keystore work; grants can change during it.
            check(checkUrl(url) == destination) { "Endpoint changed before sending" }
            val call = client.newCall(request)
            call.timeout().timeout(timeout, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val source = it.body.source()
                            source.request(JsVoiceRuntime.RESPONSE_BYTES.toLong() + 1)
                            require(source.buffer.size <= JsVoiceRuntime.RESPONSE_BYTES) { "Response is too large" }
                            buildJsonObject {
                                put("status", it.code)
                                put("ok", it.isSuccessful)
                                put("headers", buildJsonObject {
                                    it.headers.names().forEach { name ->
                                        // Never expose a credential echoed in a response header.
                                        if (!name.equals("Set-Cookie", true) && name.lowercase() !in headers.keys.map(String::lowercase))
                                            put(name, it.header(name).orEmpty())
                                    }
                                })
                                put("body", source.readUtf8())
                            }.toString()
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
    companion object {
        private val CLIENT = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(60, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    }
}

internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
