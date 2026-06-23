package com.wuyi.libraryauto.sync

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 在客户端侧强制 HTTPS：拒绝所有 `http://` 出向请求，除非目标主机是环回地址。
 *
 * 设计依据 Requirement 9.3 / 12.3：
 * - 服务端 [Active_Account_Detail_API] 仅在 HTTPS 通道下提供服务，明文请求会被服务端以
 *   `426 Upgrade Required` 拒绝。客户端在请求发出前先做相同校验，可以避免把请求发出去
 *   后才发现明文裸跑——尤其能避免 token 在调用栈中沿明文链路被打包进调试日志。
 * - 环回地址（`127.0.0.0/8` / `::1` / `localhost` 及解析为环回的主机名）放行：本机联调
 *   与服务端单测会跑在 `http://localhost:port` 上，本拦截器不应阻断这些链路。
 * - 命中明文且非环回时抛出 [HttpsRequiredException]，类型为 [IOException] 的子类，符合
 *   OkHttp `Interceptor` 协议，调用方可以按 `IOException` 统一捕获。
 */
class HttpsEnforcementInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (request.url.isHttps || isLoopback(host)) {
            return chain.proceed(request)
        }
        throw HttpsRequiredException(
            "拒绝向非环回地址发起明文 HTTP 请求：${request.url}（HTTPS 强制由 Requirement 9.3 / 12.3 决定）",
        )
    }

    private fun isLoopback(host: String): Boolean {
        if (host.isEmpty()) return false
        // 字面量快速判断，避免在常见路径上触发 DNS 解析。
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host == "::1" || host == "[::1]") return true
        if (host.startsWith("127.")) return true
        // 主机名形态（如自定义 hosts 映射的 `loop.test`）落到 InetAddress 解析；
        // 解析失败时按非环回处理，让请求显式失败而不是悄悄放行。
        return try {
            InetAddress.getByName(host).isLoopbackAddress
        } catch (_: UnknownHostException) {
            false
        } catch (_: SecurityException) {
            // SecurityManager 拒绝解析时不放行，保持「未知即拒」的安全默认。
            false
        }
    }
}

/**
 * 拦截到非环回的明文请求时抛出。继承 [IOException] 是为了和 OkHttp 的失败传播路径对齐。
 */
class HttpsRequiredException(message: String) : IOException(message)
