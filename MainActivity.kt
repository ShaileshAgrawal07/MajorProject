package com.example.beatxpconnect

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.os.postDelayed
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private lateinit var listView: ListView
    private lateinit var stepsView: TextView
    private lateinit var heartRateView: TextView
    private lateinit var distanceView: TextView
    private lateinit var calorieView: TextView



    private val deviceList = mutableListOf<BluetoothDevice>()
    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null

    private val STEP_SERVICE_UUID = UUID.fromString("0000feea-0000-1000-8000-00805f9b34fb")
    private val STEP_CHAR_UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    private val UNLOCK_CHAR_UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb")
    private val HEART_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val HEART_CHAR_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.deviceList)
        stepsView = findViewById(R.id.stepsView)
        heartRateView = findViewById(R.id.heartRateView)
        distanceView = findViewById(R.id.distanceView)
        calorieView = findViewById(R.id.calorieView)



        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        checkPermissionsAndStartScan()
    }

    private fun checkPermissionsAndStartScan() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        } else {
            startScan()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        bluetoothLeScanner.startScan(scanCallback)
        handler.postDelayed({
            bluetoothLeScanner.stopScan(scanCallback)
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!deviceList.contains(device) && device.name != null) {
                deviceList.add(device)
                updateDeviceList()
            }
        }
    }

    private fun updateDeviceList() {
        val names = deviceList.map {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            it.name ?: it.address
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val device = deviceList[position]
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val stepService = gatt.getService(STEP_SERVICE_UUID)
            val unlockChar = stepService?.getCharacteristic(UNLOCK_CHAR_UUID)

            if (unlockChar != null) {
                unlockChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                unlockChar.value = byteArrayOf(0x01)
                val success = gatt.writeCharacteristic(unlockChar)
                Log.d("BLE", "Unlock write: $success")
            }

            // Enable notifications after unlock
            handler.postDelayed({
                val stepChar = stepService?.getCharacteristic(STEP_CHAR_UUID)
                if (stepChar != null) {
                    gatt.setCharacteristicNotification(stepChar, true)
                    val descriptor = stepChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val notifySuccess = gatt.writeDescriptor(descriptor)
                    Log.d("BLE", "Notification descriptor write: $notifySuccess")
                } else {
                    Log.e("BLE", "Step characteristic not found")
                }
            }, 500)

            handler.postDelayed({
                val heartService = gatt.getService(HEART_SERVICE_UUID)
                val heartChar = heartService?.getCharacteristic(HEART_CHAR_UUID)

                if (heartChar != null) {
                    gatt.setCharacteristicNotification(heartChar, true)
                    val hrDescriptor = heartChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    hrDescriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(hrDescriptor)
                    Log.d("BLE", "Heart rate notification enabled")
                } else {
                    Log.e("BLE", "Heart rate characteristic not found")
                }
            }, 500)

        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                STEP_CHAR_UUID -> {
                    val data = characteristic.value
                    if (data.size >= 9) {
                        val steps = (data[1].toInt() and 0xFF shl 8) or (data[0].toInt() and 0xFF)
                        val distance =
                            (data[4].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
                        val calories = data[6].toInt() and 0xFF

                        runOnUiThread {
                            stepsView.text = "Steps: $steps"
                            distanceView.text = "Distance: $distance m"
                            calorieView.text = "Calories: $calories kcal"
                        }
                    } else {
                        // Fallback for old 2-byte format
                        val steps = if (data.size >= 2) {
                            (data[1].toInt() and 0xFF shl 8) or (data[0].toInt() and 0xFF)
                        } else 0

                        runOnUiThread {
                            stepsView.text = "Steps: $steps"
                        }
                    }
                }

                HEART_CHAR_UUID -> {
                    val data = characteristic.value
                    val heartRate = if ((data[0].toInt() and 0x01) == 0) {
                        data[1].toInt() and 0xFF // UINT8 format
                    } else {
                        (data[2].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF) // UINT16
                    }

                    runOnUiThread {
                        heartRateView.text = "Heart Rate: $heartRate bpm"
                    }
                }
            }
        }

        }
}
