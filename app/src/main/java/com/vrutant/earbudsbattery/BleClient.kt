package com.vrutant.earbudsbattery

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Thin wrapper around Android's BluetoothGatt APIs.
 *
 * TWS earbuds from the BBK ecosystem (realme/OPPO/OnePlus) use the OPO v1
 * protocol: you must WRITE handshake/query commands to a command
 * characteristic, and battery replies arrive as notifications on a separate
 * characteristic. Older realme buds push battery bytes without any query.
 * This class therefore exposes generic connect / discover / read / write /
 * subscribe primitives so both behaviours can be handled by the UI layer.
 */
class BleClient(private val context: Context) {

    companion object {
        private const val TAG = "BleClient"
        // Standard descriptor UUID used to enable notifications on a characteristic
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var pendingSubscribeChar: BluetoothGattCharacteristic? = null

    var onConnectionState: ((connected: Boolean) -> Unit)? = null
    var onConnectionFailed: ((status: Int) -> Unit)? = null
    var onServicesDiscovered: ((services: List<BluetoothGattService>) -> Unit)? = null
    var onCharacteristicUpdate: ((characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Unit)? = null
    var onDescriptorWrite: ((characteristic: BluetoothGattCharacteristic, success: Boolean) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
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
    fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        withoutResponse: Boolean
    ) {
        val g = gatt ?: return
        characteristic.writeType = if (withoutResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        g.writeCharacteristic(characteristic, value)
    }

    @SuppressLint("MissingPermission")
    fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        val props = characteristic.properties
        val indicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val notify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (!indicate && !notify) return
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        pendingSubscribeChar = characteristic
        descriptor.value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        g.writeDescriptor(descriptor)
    }

    private fun runOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Connection error, status=$status")
                runOnMain { onConnectionFailed?.invoke(status) }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected, discovering services...")
                onConnectionState?.invoke(true)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected")
                runOnMain { onConnectionState?.invoke(false) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onServicesDiscovered?.invoke(g.services)
            } else {
                Log.w(TAG, "Service discovery failed, status=$status")
                runOnMain { onConnectionFailed?.invoke(status) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "Descriptor write (${descriptor.uuid}) success=$success")
            val characteristic = pendingSubscribeChar
            pendingSubscribeChar = null
            if (characteristic != null) {
                runOnMain { onDescriptorWrite?.invoke(characteristic, success) }
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