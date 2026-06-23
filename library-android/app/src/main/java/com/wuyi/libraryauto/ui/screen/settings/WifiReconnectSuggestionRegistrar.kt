package com.wuyi.libraryauto.ui.screen.settings

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import com.wuyi.libraryauto.core.storage.network.WifiReconnectSnapshot

class WifiReconnectSuggestionRegistrar(
    context: Context,
) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    fun syncSuggestions(
        previousSnapshot: WifiReconnectSnapshot,
        currentSnapshot: WifiReconnectSnapshot,
    ): String {
        val previousSuggestions = previousSnapshot.toSuggestions()
        if (previousSuggestions.isNotEmpty()) {
            runCatching { wifiManager.removeNetworkSuggestions(previousSuggestions) }
        }

        if (!currentSnapshot.enabled) {
            return "已关闭后台 Wi-Fi 重连，并移除系统里的旧建议"
        }

        val currentSuggestions = currentSnapshot.toSuggestions()
        if (currentSuggestions.isEmpty()) {
            return "配置已保存，但还没有可登记给系统的 Wi-Fi"
        }

        val status =
            runCatching { wifiManager.addNetworkSuggestions(currentSuggestions) }
                .getOrElse { error ->
                    return "配置已保存，但登记系统 Wi-Fi 建议失败：${error.message ?: "未知错误"}"
                }
        return if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            "Wi-Fi 重连配置已保存，已登记系统自动连回建议"
        } else {
            "配置已保存，但系统没有接受 Wi-Fi 建议（状态码 $status）"
        }
    }

    private fun WifiReconnectSnapshot.toSuggestions(): List<WifiNetworkSuggestion> =
        buildList {
            primaryNetwork?.let(::add)
            addAll(candidateNetworks)
        }.mapNotNull(::sanitize)
            .distinctBy(WifiReconnectNetwork::ssid)
            .map { network ->
                WifiNetworkSuggestion.Builder()
                    .setSsid(network.ssid)
                    .setWpa2Passphrase(network.password)
                    .build()
            }

    private fun sanitize(network: WifiReconnectNetwork): WifiReconnectNetwork? {
        val ssid = network.ssid.trim()
        if (ssid.isBlank() || network.password.isBlank()) {
            return null
        }
        return network.copy(ssid = ssid)
    }
}
