package com.kayanne.retrocrate.data.network

import android.content.Context
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Browser-like UA — scraping etiquette per LLM-CONTEXT §13. ROM sites often block obvious bots.
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val CACHE_SIZE_BYTES = 50L * 1024L * 1024L

object HttpClient {

    @Volatile private var client: OkHttpClient? = null

    fun initialize(appContext: Context) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            val cacheDir = File(appContext.cacheDir, "http")
            client = OkHttpClient.Builder()
                .cache(Cache(cacheDir, CACHE_SIZE_BYTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(UserAgentInterceptor())
                .addInterceptor(HostRateLimitInterceptor())
                .build()
        }
    }

    fun get(): OkHttpClient = client
        ?: error("HttpClient not initialized. Call HttpClient.initialize(context) first.")
}

private class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return chain.proceed(request)
    }
}

// Per-host concurrency cap (≤2 simultaneous calls per host) — LLM-CONTEXT §13 guardrail.
private class HostRateLimitInterceptor : Interceptor {

    private val semaphores = HashMap<String, Semaphore>()

    @Synchronized
    private fun semaphoreFor(host: String): Semaphore =
        semaphores.getOrPut(host) { Semaphore(2) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = URL(chain.request().url.toString()).host
        val sem = semaphoreFor(host)
        sem.acquire()
        return try {
            chain.proceed(chain.request())
        } finally {
            sem.release()
        }
    }
}
