package com.wuyi.libraryauto.core.ble

object IBeaconParser {
    private const val appleIBeaconCompanyId = 0x004C
    private const val iBeaconPayloadLength = 22
    private const val prefixFirstByte = 0x02
    private const val prefixSecondByte = 0x15
    private const val minorOffset = 20

    fun extractMinor(manufacturerData: Map<Int, ByteArray>?): Int? {
        val payload = manufacturerData?.get(appleIBeaconCompanyId) ?: return null
        if (payload.size < iBeaconPayloadLength) {
            return null
        }
        if (payload[0] != prefixFirstByte.toByte() || payload[1] != prefixSecondByte.toByte()) {
            return null
        }
        return ((payload[minorOffset].toInt() and 0xFF) shl 8) or
            (payload[minorOffset + 1].toInt() and 0xFF)
    }
}
