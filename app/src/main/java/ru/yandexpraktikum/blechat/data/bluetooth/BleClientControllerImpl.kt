package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val viewModelScope: CoroutineScope,
) : BleClientController {

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private var currentGatt : BluetoothGatt? = null

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()


    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status != BluetoothGatt.GATT_SUCCESS) {

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        viewModelScope.launch {
                            withTimeout(TIMEOUT) {
                                context.checkForConnectPermission {
                                    gatt?.discoverServices()
                                }
                            }
                        }
                        _scannedDevices.update { devices ->
                            devices.map {
                                if (it.address == gatt?.device?.address) {
                                    currentGatt = gatt
                                    it.copy(isConnected = true)
                                } else {
                                    it
                                }
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        closeConnection()
                    }
                }
            }
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE", "Scan failed with error code: $errorCode")
        }
    }

    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value =
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Location state", e)
        }
    }

    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        var foundedDevice: BluetoothDevice? = null

        context.checkForConnectPermission {
            foundedDevice = bluetoothAdapter?.bondedDevices?.find {
                it.address == device.address
            }
        }

        return try {
            context.checkForConnectPermission {
                viewModelScope.launch {
                    withTimeout(TIMEOUT) {
                        runCatching {
                            foundedDevice?.connectGatt(context, true, gattCallback)
                        }.onFailure { e ->
                            Log.e(TAG, e.message.toString())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message.toString())
            false
        } as Boolean
    }

    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        TODO()
    }

    override fun closeConnection() {
        context.checkForConnectPermission {
            currentGatt?.close()
        }
        currentGatt = null
    }

    override fun release() {
        closeConnection()
    }

    private companion object {
        val TAG = BleClientControllerImpl::class.simpleName
        const val TIMEOUT = 5_000L
    }
}