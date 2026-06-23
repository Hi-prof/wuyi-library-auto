package com.wuyi.libraryauto.core.network.http

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 进程级共享的 [OkHttpClient] 内核：所有 [OkHttpSchoolHttpClient] 实例通过
 * `client.newBuilder()` 派生时复用同一个 dispatcher、连接池和线程池，避免 20+ 账号
 * 同时跑后台签到/查询时大量重复创建 OkHttp 内部资源。
 *
 * 派生实例可以独立设置自己的 [okhttp3.CookieJar]，每个账号的 cookie 仍然按实例隔离，
 * 不会串账号。这里不持有 cookieJar，让派生方决定。
 */
internal object SharedHttpClientCore {
    /**
     * 共享 OkHttp 客户端。
     *
     * - 显式超时与原有 [OkHttpSchoolHttpClient] 保持一致；
     * - 不在此处加 cookieJar、拦截器，避免影响认证流程；
     * - 用 lazy 延迟到首次实际请求时才初始化，单测可以 mock 派生客户端而不触发实例化。
     */
    val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val DEFAULT_TIMEOUT_SECONDS = 15L
}
