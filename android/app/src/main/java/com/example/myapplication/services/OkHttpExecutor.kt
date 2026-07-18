package com.example.myapplication.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** OkHttp 实现的 HttpExecutor（ADR-0001 端点调用）。 */
class OkHttpExecutor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : HttpExecutor {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            HttpResponse(resp.code, resp.body?.string() ?: "")
        }
    }
}
