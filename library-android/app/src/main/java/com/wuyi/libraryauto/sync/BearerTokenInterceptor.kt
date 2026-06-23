package com.wuyi.libraryauto.sync

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 为 [AccountPoolApi] 的每个出向请求注入 `Authorization: Bearer <token>` 头。
 *
 * 设计契约：
 * - token 通过 [tokenProvider] 提供，每次请求都重新读取，便于运行期换发 token 后立即生效，
 *   不需要重建 [okhttp3.OkHttpClient]。
 * - 当 [tokenProvider] 返回空串或 `null` 时**不**写入 `Authorization` 头：让上层显式承担
 *   未配置 token 的错误处理（接口会回 401），避免本地无声构造 `Bearer ` 形式的脏 token。
 * - 不修改请求体或其他头部，对鉴权失败 / 限频 / HTTPS 错误的响应仅原样透传，由调用方处理。
 */
class BearerTokenInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isEmpty()) {
            return chain.proceed(original)
        }
        val authorized =
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        return chain.proceed(authorized)
    }
}
