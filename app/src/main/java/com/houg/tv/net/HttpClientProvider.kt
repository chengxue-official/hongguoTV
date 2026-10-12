package com.houg.tv.net

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    @Volatile
    private var client: OkHttpClient? = null

    fun get(@Suppress("UNUSED_PARAMETER") context: Context? = null): OkHttpClient {
        val existing = client
        if (existing != null) return existing
        synchronized(this) {
            val again = client
            if (again != null) return again
            val built = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            client = built
            return built
        }
    }
}
