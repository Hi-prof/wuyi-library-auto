package com.wuyi.libraryauto.core.ble

// BLE 层只汇报扫描观察结果，命中判断和签到结果由 domain 负责。
internal data class BleScanResult(
    val seenMinors: List<Int>,
)
