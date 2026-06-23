package com.wuyi.libraryauto.core.network.captive

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 目标域可达性探测器接口。
 *
 * 用途：供 `NetworkMonitorMetricsRepository` 等聚合层声明依赖；
 * 真正的 HTTP HEAD 5s connect/read、TLS 失败分类等实现由 [OkHttpTargetReachabilityProbe] 提供。
 *
 * 关键入参：[probe] 接受目标 URL（默认指向学校域名 `wuyiu.huitu.zhishulib.com`）。
 * 返回值：[ProbeResult] 描述本次探测是否成功、耗时与失败分类。
 */
interface TargetReachabilityProbe {
    /**
     * 探测目标 URL 的可达性。
     *
     * 关键入参：[url] 目标 URL，默认值与设计文档一致。
     * 返回值：[ProbeResult] 至少包含 `reachable` 布尔值、`durationMillis` 探测耗时与可空的 [ProbeResult.FailureReason]。
     */
    suspend fun probe(url: String = DEFAULT_URL): ProbeResult

    companion object {
        /** 设计文档约定的默认探测目标。 */
        const val DEFAULT_URL: String = "https://wuyiu.huitu.zhishulib.com/"
    }
}

/**
 * 目标域可达性探测结果。
 *
 * 关键字段：
 * - [reachable]：探测是否成功（即便 HTTP 返回 404 等也视情而定，由实现方决定）。
 * - [durationMillis]：从发起请求到收到结果或失败的耗时毫秒数。
 * - [failureReason]：失败分类，仅在 [reachable] 为 false 时使用。
 */
data class ProbeResult(
    val reachable: Boolean,
    val durationMillis: Long,
    val failureReason: FailureReason? = null,
) {
    /** 探测失败的原因分类，对齐 R15.3 / R15.4。 */
    enum class FailureReason {
        /** 5s 内未收到响应。 */
        TIMEOUT,

        /** 网络不可达 / 拒绝连接。 */
        NETWORK_UNREACHABLE,

        /** TLS 握手失败、证书校验失败等。 */
        CERTIFICATE_ERROR,

        /** 其他失败。 */
        OTHER,
    }
}

/**
 * 基于 OkHttp 的 [TargetReachabilityProbe] 默认实现。
 *
 * 行为约束（对齐设计文档 §`TargetReachabilityProbe` 与需求 R12.7 / R15.2 / R15.3 / R15.4）：
 * - 使用 `HEAD` 方法发起请求，避免下载响应体；
 * - 5s connectTimeout + 5s readTimeout + 5s writeTimeout，整体 callTimeout 10s 兜底；
 * - 任意 2xx / 3xx / 4xx（含 401/403/404）只要拿到响应即视为"网络层可达"，
 *   captive portal 重定向也由此归属"可达"，业务层判定见 [com.wuyi.libraryauto.core.network.captive] 的认证模块；
 * - TLS 失败（握手 / 证书校验）直接归类为 [ProbeResult.FailureReason.CERTIFICATE_ERROR]，
 *   严禁降级为忽略证书的请求方式（R15.4）；
 * - 任何异常都被吞掉并映射为 `reachable=false` 的 [ProbeResult]，绝不向上抛异常（R15.3）。
 *
 * 关键入参：[client] 注入的 OkHttp 客户端，便于单元测试用 MockWebServer 替换。
 *
 * 注意：构造方需保证传入的 [client] 已配置满足 5s 超时；如不确定，可直接使用 [default] 工厂。
 */
class OkHttpTargetReachabilityProbe(
    private val client: OkHttpClient,
) : TargetReachabilityProbe {

    override suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        val startNanos = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val elapsed = elapsedMillisSince(startNanos)
                // HTTP 协议层面成功响应即视为目标可达；captive portal 重定向（302 / 200+HTML）
                // 也属于"网络层能到达目标"，是否需要认证由 CampusPortalAuthenticator 在内容层判定。
                // 设计文档约定：2xx-3xx 直接可达；401 / 403 / 404 同样视为"网络层可达"——
                // 服务器明确响应即说明 TCP/TLS/HTTP 三层均工作正常，仅业务/路径层面拒绝。
                ProbeResult(
                    reachable = isReachableHttpCode(response.code),
                    durationMillis = elapsed,
                    failureReason = null,
                )
            }
        } catch (cause: SSLHandshakeException) {
            failureResult(startNanos, ProbeResult.FailureReason.CERTIFICATE_ERROR)
        } catch (cause: SSLPeerUnverifiedException) {
            failureResult(startNanos, ProbeResult.FailureReason.CERTIFICATE_ERROR)
        } catch (cause: CertificateException) {
            failureResult(startNanos, ProbeResult.FailureReason.CERTIFICATE_ERROR)
        } catch (cause: SocketTimeoutException) {
            failureResult(startNanos, ProbeResult.FailureReason.TIMEOUT)
        } catch (cause: InterruptedIOException) {
            // OkHttp 在 callTimeout 触发时抛 InterruptedIOException，归入超时分类。
            failureResult(startNanos, ProbeResult.FailureReason.TIMEOUT)
        } catch (cause: ConnectException) {
            failureResult(startNanos, ProbeResult.FailureReason.NETWORK_UNREACHABLE)
        } catch (cause: UnknownHostException) {
            failureResult(startNanos, ProbeResult.FailureReason.NETWORK_UNREACHABLE)
        } catch (cause: IOException) {
            failureResult(startNanos, ProbeResult.FailureReason.OTHER)
        }
    }

    private fun failureResult(
        startNanos: Long,
        reason: ProbeResult.FailureReason,
    ): ProbeResult = ProbeResult(
        reachable = false,
        durationMillis = elapsedMillisSince(startNanos),
        failureReason = reason,
    )

    private fun elapsedMillisSince(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    companion object {
        /**
         * 4xx 中仍视作"网络层可达"的状态码集合。
         *
         * 401 / 403 表示服务器在线只是拒绝授权；404 表示服务器在线只是路径不存在；
         * 这些都说明 TCP/TLS 握手与 HTTP 应用层均工作正常，故归属"可达"。
         */
        private val NON_2XX_REACHABLE_CODES: Set<Int> = setOf(401, 403, 404)

        /**
         * 给定 HTTP 状态码是否视作"网络层可达"。
         *
         * 规则（对齐 design §`TargetReachabilityProbe`）：
         * - 2xx / 3xx：必然可达；3xx 通常是 captive portal 重定向。
         * - 401 / 403 / 404：服务器明确响应，TCP/TLS/HTTP 链路畅通，仅授权 / 路径问题，仍视作可达。
         * - 其他 4xx / 5xx：保守地不算"可达"，需要业务层关注。
         */
        internal fun isReachableHttpCode(code: Int): Boolean =
            code in REACHABLE_CODE_RANGE || code in NON_2XX_REACHABLE_CODES

        /** 2xx-3xx 全段都视作可达。 */
        private val REACHABLE_CODE_RANGE: IntRange = 200..399

        /** 默认 5s connect / read / write 超时与 10s callTimeout，符合 R15.2。 */
        private const val DEFAULT_TIMEOUT_SECONDS: Long = 5L

        /** 整体 callTimeout 兜底，避免连接 + 读取 + 重定向叠加超过预期。 */
        private const val DEFAULT_CALL_TIMEOUT_SECONDS: Long = 10L

        /**
         * 创建一个使用默认超时配置的 [OkHttpTargetReachabilityProbe]。
         *
         * 关键入参：无；返回值：已配置 5s 各项超时的实例。
         * 如调用方有自己的 OkHttp 共享池，可绕过本工厂直接构造。
         */
        fun default(): OkHttpTargetReachabilityProbe {
            val client = OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // 显式禁用重定向跟随：captive portal 的 302 应在第一次响应被业务层捕获，
                // 并避免 HEAD 请求在重定向链中被服务端拒绝。
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            return OkHttpTargetReachabilityProbe(client)
        }
    }
}
