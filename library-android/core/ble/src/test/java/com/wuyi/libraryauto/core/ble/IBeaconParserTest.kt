package com.wuyi.libraryauto.core.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IBeaconParserTest {
    @Test
    fun `extractMinor returns apple ibeacon minor 58`() {
        val manufacturerData = mapOf(0x004C to ibeaconPayload(0x003A))

        val minor: Int? = IBeaconParser.extractMinor(manufacturerData)

        assertThat(minor).isEqualTo(58)
    }

    @Test
    fun `extractMinor returns null for invalid payload`() {
        val invalidPayload = mapOf(
            0x004C to byteArrayOf(0x02, 0x15, 0x00),
        )

        val minor: Int? = IBeaconParser.extractMinor(invalidPayload)

        assertThat(minor == null).isTrue()
    }

    @Test
    fun `extractMinor returns null for wrong company id`() {
        val manufacturerData = mapOf(0x1234 to ibeaconPayload(0x003A))

        val minor: Int? = IBeaconParser.extractMinor(manufacturerData)

        assertThat(minor == null).isTrue()
    }

    @Test
    fun `extractMinor returns null for wrong prefix`() {
        val manufacturerData = mapOf(
            0x004C to ibeaconPayload(0x003A).also {
                it[0] = 0x01
            },
        )

        val minor: Int? = IBeaconParser.extractMinor(manufacturerData)

        assertThat(minor == null).isTrue()
    }

    @Test
    fun `extractMinor accepts exact 22 byte payload`() {
        val manufacturerData = mapOf(0x004C to ibeaconPayload(0x003A))

        val minor: Int? = IBeaconParser.extractMinor(manufacturerData)

        assertThat(manufacturerData.getValue(0x004C).size).isEqualTo(22)
        assertThat(minor).isEqualTo(58)
    }

    @Test
    fun `extractMinor parses high byte minor`() {
        val manufacturerData = mapOf(0x004C to ibeaconPayload(0x1234))

        val minor: Int? = IBeaconParser.extractMinor(manufacturerData)

        assertThat(minor).isEqualTo(0x1234)
    }

    private fun ibeaconPayload(minor: Int): ByteArray =
        ByteArray(22).apply {
            this[0] = 0x02
            this[1] = 0x15
            this[20] = ((minor shr 8) and 0xFF).toByte()
            this[21] = (minor and 0xFF).toByte()
        }
}
