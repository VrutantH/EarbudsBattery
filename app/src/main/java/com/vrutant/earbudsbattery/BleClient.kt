package com.vrutant.earbudsbattery

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Thin wrapper around Android's BluetoothGatt APIs.
 *
 * Notes on the "why" here, since TWS earbuds don't use standard GATT battery
 * services: most vendors (Realme/OPPO/OnePlus included) expose battery data
 * through a proprietary service + characteristic. This class doesn't assume
 * any UUIDs — it just gives you generic connect / discover / read / subscribe
 * primitives so the Explorer screen can find the right one, and the Dashboard
 * screen can use it once you've identified it.
 */
class BleClient(private val context: Context) {

    companion object {
        private const val TAG = "BleClient"
        // Standard descriptor UUID used to enable notifications on a characteristic
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null

    var onConnectionState: ((connected: Boolean) -> Unit)? = null
    var onServicesDiscovered: ((services: List<BluetoothGattService>) -> Unit)? = null
    var onCharacteristicUpdate: ((characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        gatt?.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(descriptor)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected, discovering services...")
                onConnectionState?.invoke(true)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected")
                onConnectionState?.invoke(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onServicesDiscovered?.invoke(g.services)
            }
        }

        @Deprecated("Deprecated in API 33, kept for back-compat down to minSdk 26")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onCharacteristicUpdate?.invoke(characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        @Deprecated("Deprecated in API 33, kept for back-compat down to minSdk 26")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onCharacteristicUpdate?.invoke(characteristic, characteristic.value ?: ByteArray(0))
        }
    }
}

/** Converts a byte array to a readable hex string, e.g. "4C 5F 32 00" */
fun ByteArray.toHexString(): String =
    joinToString(" ") { String.format("%02X", it) }
