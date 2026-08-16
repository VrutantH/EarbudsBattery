package com.vrutant.earbudsbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrutant.earbudsbattery.databinding.ActivityMainBinding
import com.vrutant.earbudsbattery.databinding.ItemCharacteristicBinding
import com.vrutant.earbudsbattery.databinding.ItemDeviceBinding
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleClient: BleClient
    private lateinit var prefs: android.content.SharedPreferences

    private var connectedDevice: BluetoothDevice? = null
    private val discoveredCharacteristics = mutableListOf<Pair<BluetoothGattService, BluetoothGattCharacteristic>>()
    private val characteristicValues = mutableMapOf<UUID, ByteArray>()

    // A characteristic can host battery data for multiple states; we key the
    // saved mapping by its UUID string once you've confirmed it in Explorer.
    private var mappedCharacteristic: BluetoothGattCharacteristic? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            loadPairedDevices()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to scan for your earbuds", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("earbuds_battery_prefs", Context.MODE_PRIVATE)
        bleClient = BleClient(this)

        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.characteristicList.layoutManager = LinearLayoutManager(this)

        binding.scanButton.setOnClickListener { requestPermissionsAndScan() }
        binding.showExplorerBtn.setOnClickListener { showSection(explorer = true) }
        binding.showDashboardBtn.setOnClickListener { showSection(explorer = false) }
        binding.disconnectBtn.setOnClickListener { disconnectDevice() }
        binding.saveMappingBtn.setOnClickListener { saveMappingAndSubscribe() }

        wireBleCallbacks()
        restoreSavedMapping()
        requestPermissionsAndScan()
    }

    // ---------- Permissions ----------

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestPermissionsAndScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            loadPairedDevices()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- Paired device list ----------

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bluetoothManager.adapter ?: run {
            Toast.makeText(this, "This device has no Bluetooth adapter", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Turn on Bluetooth and pair your earbuds first, then tap Scan again", Toast.LENGTH_LONG).show()
            return
        }
        val paired = adapter.bondedDevices?.toList() ?: emptyList()
        binding.deviceList.adapter = DeviceAdapter(paired) { device -> connectToDevice(device) }
        binding.statusText.text = "Found ${paired.size} paired device(s). Tap your earbuds/case to connect."
    }

    // ---------- Connection ----------

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        binding.statusText.text = "Connecting to ${device.name ?: device.address}..."
        connectedDevice = device
        bleClient.connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        bleClient.disconnect()
        connectedDevice = null
        discoveredCharacteristics.clear()
        characteristicValues.clear()
        binding.scanSection.visibility = View.VISIBLE
        binding.modeToggle.visibility = View.GONE
        binding.explorerSection.visibility = View.GONE
        binding.dashboardSection.visibility = View.GONE
        binding.statusText.text = "Not connected"
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun wireBleCallbacks() {
        bleClient.onConnectionState = { connected ->
            runOnUiThread {
                if (connected) {
                    val name = connectedDevice?.name ?: connectedDevice?.address ?: "device"
                    binding.statusText.text = "Connected to $name — discovering services..."
                    binding.scanSection.visibility = View.GONE
                    binding.modeToggle.visibility = View.VISIBLE
                    showSection(explorer = true)
                } else {
                    binding.statusText.text = "Disconnected"
                }
            }
        }

        bleClient.onServicesDiscovered = { services ->
            runOnUiThread {
                discoveredCharacteristics.clear()
                for (service in services) {
                    for (characteristic in service.characteristics) {
                        discoveredCharacteristics.add(service to characteristic)
                    }
                }
                binding.statusText.text = "Connected — ${discoveredCharacteristics.size} characteristics found across ${services.size} services"
                binding.characteristicList.adapter = CharacteristicAdapter(
                    discoveredCharacteristics,
                    characteristicValues
                ) { characteristic ->
                    // Try reading first; if not readable, subscribe to notifications instead.
                    val props = characteristic.properties
                    if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        bleClient.readCharacteristic(characteristic)
                    }
                    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        bleClient.subscribeToCharacteristic(characteristic)
                    }
                }
                reapplyMappedSubscriptionIfNeeded()
            }
        }

        bleClient.onCharacteristicUpdate = { characteristic, value ->
            runOnUiThread {
                characteristicValues[characteristic.uuid] = value
                binding.characteristicList.adapter?.notifyDataSetChanged()

                if (mappedCharacteristic?.uuid == characteristic.uuid) {
                    updateDashboard(value)
                }
            }
        }
    }

    // ---------- Section switching ----------

    private fun showSection(explorer: Boolean) {
        binding.explorerSection.visibility = if (explorer) View.VISIBLE else View.GONE
        binding.dashboardSection.visibility = if (explorer) View.GONE else View.VISIBLE
    }

    // ---------- Dashboard mapping ----------

    private fun restoreSavedMapping() {
        val uuid = prefs.getString("mapped_uuid", null) ?: return
        binding.uuidInput.setText(uuid)
        binding.leftByteInput.setText(prefs.getInt("left_byte", 0).toString())
        binding.rightByteInput.setText(prefs.getInt("right_byte", 1).toString())
        binding.caseByteInput.setText(prefs.getInt("case_byte", 2).toString())
    }

    private fun saveMappingAndSubscribe() {
        val uuidText = binding.uuidInput.text.toString().trim()
        if (uuidText.isEmpty()) {
            Toast.makeText(this, "Enter the characteristic UUID you identified in Explorer", Toast.LENGTH_LONG).show()
            return
        }
        val leftByte = binding.leftByteInput.text.toString().toIntOrNull() ?: 0
        val rightByte = binding.rightByteInput.text.toString().toIntOrNull() ?: 1
        val caseByte = binding.caseByteInput.text.toString().toIntOrNull() ?: 2

        prefs.edit()
            .putString("mapped_uuid", uuidText)
            .putInt("left_byte", leftByte)
            .putInt("right_byte", rightByte)
            .putInt("case_byte", caseByte)
            .apply()

        reapplyMappedSubscriptionIfNeeded()
        Toast.makeText(this, "Mapping saved", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun reapplyMappedSubscriptionIfNeeded() {
        val uuidText = prefs.getString("mapped_uuid", null) ?: return
        val uuid = try { UUID.fromString(uuidText) } catch (e: Exception) { return }
        val match = discoveredCharacteristics.firstOrNull { it.second.uuid == uuid } ?: return
        mappedCharacteristic = match.second
        bleClient.readCharacteristic(match.second)
        bleClient.subscribeToCharacteristic(match.second)
    }

    private fun updateDashboard(value: ByteArray) {
        val leftByte = prefs.getInt("left_byte", 0)
        val rightByte = prefs.getInt("right_byte", 1)
        val caseByte = prefs.getInt("case_byte", 2)

        fun byteOrNull(index: Int): Int? =
            if (index in value.indices) value[index].toInt() and 0xFF else null

        byteOrNull(leftByte)?.let {
            val pct = it.coerceIn(0, 100)
            binding.leftBar.progress = pct
            binding.leftLabel.text = "$pct %"
        }
        byteOrNull(rightByte)?.let {
            val pct = it.coerceIn(0, 100)
            binding.rightBar.progress = pct
            binding.rightLabel.text = "$pct %"
        }
        byteOrNull(caseByte)?.let {
            val pct = it.coerceIn(0, 100)
            binding.caseBar.progress = pct
            binding.caseLabel.text = "$pct %"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnect()
    }

    // ---------- Adapters ----------

    class DeviceAdapter(
        private val devices: List<BluetoothDevice>,
        private val onClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.binding.deviceName.text = device.name ?: "(unnamed device)"
            holder.binding.deviceAddress.text = device.address
            holder.binding.root.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = devices.size
    }

    class CharacteristicAdapter(
        private val items: List<Pair<BluetoothGattService, BluetoothGattCharacteristic>>,
        private val values: Map<UUID, ByteArray>,
        private val onClick: (BluetoothGattCharacteristic) -> Unit
    ) : RecyclerView.Adapter<CharacteristicAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemCharacteristicBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCharacteristicBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (service, characteristic) = items[position]
            holder.binding.serviceUuid.text = "service: ${service.uuid}"
            holder.binding.charUuid.text = characteristic.uuid.toString()
            holder.binding.charProps.text = describeProperties(characteristic.properties)

            val value = values[characteristic.uuid]
            holder.binding.charValue.text = if (value != null) {
                "hex: ${value.toHexString()}   decimal bytes: ${value.map { it.toInt() and 0xFF }}"
            } else {
                "(no value yet — tap to read/subscribe)"
            }

            holder.binding.root.setOnClickListener { onClick(characteristic) }
        }

        override fun getItemCount() = items.size

        private fun describeProperties(props: Int): String {
            val list = mutableListOf<String>()
            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
            return if (list.isEmpty()) "no read/write/notify" else list.joinToString(", ")
        }
    }
}
