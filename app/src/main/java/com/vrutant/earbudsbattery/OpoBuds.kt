package com.vrutant.earbudsbattery

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * OPO v1 protocol — the proprietary BLE protocol shared by the whole
 * BBK Electronics audio ecosystem (realme / OPPO / OnePlus).
 *
 * Confirmed on OnePlus Nord Buds 3 Pro and used across realme Buds
 * (Air 3 / Air 5 Pro / TWS Air 7 ...), OPPO Enco and OnePlus Buds models.
 *
 * The earbuds NEVER push battery values on their own. You must:
 *   1. subscribe to the notify characteristic
 *   2. send HELLO, wait ~1.5s
 *   3. send REGISTER (auth token), wait ~1.5s
 *   4. send QUERY_BATTERY — the answer arrives as a notification
 *
 * Battery response packet:
 *   AA LEN 00 00 06 81 SEQ 06 00 00 COUNT [ID LEVEL]...
 *   COUNT at byte[10], then (device_id, battery_percent) pairs.
 *   ID: 0x01 = Left bud, 0x02 = Right bud, 0x03 = Case.
 */
object OpoBuds {

    val SERVICE_UUID: UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")

    // Alternate UUIDs used by some firmware revisions of the same protocol
    val WRITE_CHAR_UUID_V2: UUID = UUID.fromString("0100079A-D102-11E1-9B23-00025B00A5A5")
    val NOTIFY_CHAR_UUID_V2: UUID = UUID.fromString("0200079A-D102-11E1-9B23-00025B00A5A5")

    const val ID_LEFT = 0x01
    const val ID_RIGHT = 0x02
    const val ID_CASE = 0x03

    private const val CAT_BATTERY = 0x06

    // Session setup + query packets (reverse engineered from HCI snoop logs
    // of the official HeyMelody / realme Link apps).
    val HELLO: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x07, 0x00, 0x00, 0x00, 0x01, 0x23, 0x00, 0x00, 0x12
    )
    val REGISTER: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x0C, 0x00, 0x00, 0x00, 0x85.toByte(), 0x41, 0x05, 0x00, 0x00,
        0xB5.toByte(), 0x50, 0xA0.toByte(), 0x69
    )
    val QUERY_BATTERY: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x07, 0x00, 0x00, 0x06, 0x01, 0x25, 0x00, 0x00
    )

    /** True if a discovered service looks like the OPO protocol service. */
    fun isOpoService(service: BluetoothGattService): Boolean {
        if (service.uuid == SERVICE_UUID) return true
        val uuids = service.characteristics.map { it.uuid }
        val hasWrite = uuids.any { it == WRITE_CHAR_UUID || it == WRITE_CHAR_UUID_V2 }
        val hasNotify = uuids.any { it == NOTIFY_CHAR_UUID || it == NOTIFY_CHAR_UUID_V2 }
        return hasWrite && hasNotify
    }

    /** Finds the command (write) characteristic for an OPO service. */
    fun findWriteCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        for (c in service.characteristics) {
            if (c.uuid == WRITE_CHAR_UUID || c.uuid == WRITE_CHAR_UUID_V2) {
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) return c
            }
        }
        for (c in service.characteristics) {
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) return c
        }
        return null
    }

    /** Finds the response (notify) characteristic for an OPO service. */
    fun findNotifyCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        for (c in service.characteristics) {
            if (c.uuid == NOTIFY_CHAR_UUID || c.uuid == NOTIFY_CHAR_UUID_V2) {
                val props = c.properties
                if (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) return c
            }
        }
        for (c in service.characteristics) {
            val props = c.properties
            if (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) return c
        }
        return null
    }

    /**
     * Parses an OPO battery notification.
     * @return map of device id (0x01 left / 0x02 right / 0x03 case) -> percent,
     *         or null if the packet isn't a battery response.
     */
    fun parseBatteryResponse(data: ByteArray): Map<Int, Int>? {
        if (data.size < 12) return null
        if ((data[0].toInt() and 0xFF) != 0xAA) return null
        if ((data[4].toInt() and 0xFF) != CAT_BATTERY) return null

        val count = data[10].toInt() and 0xFF
        if (count == 0) return null

        val result = HashMap<Int, Int>()
        var idx = 11
        repeat(count) {
            if (idx + 1 >= data.size) return@repeat
            val id = data[idx].toInt() and 0xFF
            val level = data[idx + 1].toInt() and 0xFF
            result[id] = level.coerceIn(0, 100)
            idx += 2
        }
        return if (result.isEmpty()) null else result
    }
}