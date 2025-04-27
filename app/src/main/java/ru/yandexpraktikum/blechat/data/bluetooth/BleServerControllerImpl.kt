package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.yandexpraktikum.blechat.domain.bluetooth.BleServerController
import ru.yandexpraktikum.blechat.domain.model.Message
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import ru.yandexpraktikum.blechat.utils.writeCharUUID
import java.nio.charset.Charset
import java.util.UUID
import javax.inject.Inject

class BleServerControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothManager: BluetoothManager?,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val viewModelScope: CoroutineScope
): BleServerController {

    private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gattServer: BluetoothGattServer? = null
    private var gattServerCallback: BluetoothGattServerCallback? = null

    private val bluetoothLeAdvertiser by lazy {
        bluetoothAdapter?.bluetoothLeAdvertiser
    }

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()


    init {
        initializeBluetoothState()
    }

    private fun initializeBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    private val _connectedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val connectedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _connectedDevices.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BLE", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Advertising failed with error: $errorCode")
        }
    }

    override fun startAdvertising() {

        if (!bluetoothAdapter?.isEnabled!!) {
            Log.e("BLE", "Bluetooth is not enabled")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BLE", "Bluetooth advertise permission not granted")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to start advertising", e)
        }
    }


    override fun stopAdvertising() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.d("BLE", "Advertising stopped")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to stop advertising", e.fillInStackTrace())
        }
    }

    @Suppress("t")
    override fun startServer() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        gattServerCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: android.bluetooth.BluetoothDevice?,
                status: Int,
                newState: Int
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        context.checkForConnectPermission {
                            _connectedDevices.update { devices ->
                                if (devices.none { it.address == device?.address } && device != null) {
                                    devices + ScannedBluetoothDevice(
                                        name = device.name,
                                        address = device.address
                                    )
                                } else devices
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _connectedDevices.update { devices ->
                            devices - devices.first { it.address == device?.address }
                        }
                    }
                }
            }


            override fun onCharacteristicWriteRequest(
                device: android.bluetooth.BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (characteristic?.uuid == writeCharUUID) {
                    context.checkForConnectPermission {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    val message = value?.let { String(it, Charset.defaultCharset()) }
                    viewModelScope.launch {
                        message?.let {
                            _connectedDevices.update { devices ->
                                devices.map {
                                    if (it.address == device?.address) {
                                        it.copy(
                                            messages = it.messages + Message(
                                                text = message,
                                                senderAddress = device.address,
                                                isFromLocalUser = false
                                            )
                                        )
                                    } else it
                                }
                            }
                        }
                    }
                }
            }
        }

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeCharacteristic = BluetoothGattCharacteristic(
            writeCharUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyCharacteristic = BluetoothGattCharacteristic(
            notifyCharUUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val descriptor = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyCharacteristic.addDescriptor(descriptor)

        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notifyCharacteristic)
        gattServer?.addService(service)

        startAdvertising()
    }

    override fun stopServer() {
        try {
            stopAdvertising()
            context.checkForConnectPermission {
                gattServer?.close()
            }
            gattServer = null
            gattServerCallback = null
            Log.d("BLE", "Server stopped successfully")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to stop server", e)
        }
    }

    @Suppress("t")
    override suspend fun sendServerMessage(message: String, deviceAddress: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        val service = gattServer?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(notifyCharUUID)

        return try {
            if (characteristic != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    characteristic.setValue(message.toByteArray(Charset.defaultCharset()))
                }
                val success =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                    } else {
                        gattServer?.notifyCharacteristicChanged(device!!, characteristic, false, message.toByteArray(Charset.defaultCharset()))
                   }
                Log.i("BLE", "Server message sent: $success")
                _connectedDevices.update { devices ->
                    devices.map {
                        if (it.address == deviceAddress) {
                            it.copy(
                                messages = it.messages + Message(
                                    text = message,
                                    senderAddress = bluetoothAdapter?.address ?: "",
                                    isFromLocalUser = true
                                )
                            )
                        } else it
                    }
                }
                true
            } else {
                Log.e("BLE", "Characteristic not found")
                false
            }
        } catch (e: Exception) {
            Log.e("BLE", "Failed to send server message", e)
            false
        }
    }

    override fun closeConnection() {
        context.checkForConnectPermission {
            gattServer?.close()
        }
        gattServer = null
    }

    override fun release() {
        closeConnection()
    }
}