package com.lilly.ble

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.lilly.ble.ui.main.MainActivity
import com.lilly.ble.util.BluetoothUtils
import com.lilly.ble.util.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.*
import kotlin.concurrent.schedule

class

BleRepository: Service() {

    private val TAG = "Central"

    // ble manager
    val bleManager: BluetoothManager =
        MyApplication.applicationContext().getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    // ble adapter
    val bleAdapter: BluetoothAdapter?
        get() = bleManager.adapter
    // ble Gatt
    private var bleGatt: BluetoothGatt? = null

    // scan results
    var scanResults: ArrayList<BluetoothDevice>? = ArrayList()

    var statusTxt: String = ""
    var txtRead: String = ""

    var isStatusChange: Boolean = false
    var isTxtRead: Boolean = false
    fun fetchReadText() = flow{
        while(true) {
            if(isTxtRead) {
                emit(txtRead)
                isTxtRead = false
            }
        }
    }.flowOn(Dispatchers.Default)
    fun fetchStatusText() = flow{
        while(true) {
            if(isStatusChange) {
                emit(statusTxt)
                isStatusChange = false
            }
        }
    }.flowOn(Dispatchers.Default)


    val requestEnableBLE = MutableLiveData<Event<Boolean>>()
    val isScanning = MutableLiveData(Event(false))
    val isConnect = MutableLiveData(Event(false))
    val listUpdate = MutableLiveData<Event<ArrayList<BluetoothDevice>?>>()
    val scrollDown = MutableLiveData<Event<Boolean>>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMyForeground();
        return START_NOT_STICKY
    }

    fun startScan() {
        // check ble adapter and ble enabled
        if (bleAdapter == null || !bleAdapter?.isEnabled!!) {
            requestEnableBLE.postValue(Event(true))
            statusTxt ="Scanning Failed: ble not enabled"
            isStatusChange = true
            return
        }
        //scan filter
        val filters: MutableList<ScanFilter> = ArrayList()
        val scanFilter: ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_STRING)))
            .build()
        filters.add(scanFilter)
        // scan settings
        // set low power scan mode
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        // start scan
        bleAdapter?.bluetoothLeScanner?.startScan(filters, settings, BLEScanCallback)
        //bleAdapter?.bluetoothLeScanner?.startScan(BLEScanCallback)

        statusTxt = "Scanning...."
        isStatusChange = true
        isScanning.postValue(Event(true))

        Timer("SettingUp", false).schedule(3000) { stopScan() }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun stopScan(){
        bleAdapter?.bluetoothLeScanner?.stopScan(BLEScanCallback)
        isScanning.postValue(Event(false))
        statusTxt = "Scan finished. Click on the name to connect to the device."
        isStatusChange = true


        scanResults = ArrayList() //list 초기화
        Log.d(TAG, "BLE Stop!")
    }

    /**
     * BLE Scan Callback
     */
    private val BLEScanCallback: ScanCallback = @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.i(TAG, "Remote device name: " + result.device.name)
            addScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            for (result in results) {
                addScanResult(result)
            }
        }

        override fun onScanFailed(_error: Int) {
            Log.e(TAG, "BLE scan failed with code $_error")
            statusTxt = "BLE scan failed with code $_error"
            isStatusChange = true
        }

        /**
         * Add scan result
         */
        private fun addScanResult(result: ScanResult) {
            // get scanned device
            val device = result.device
            // get scanned device MAC address
            val deviceAddress = device.address
            val deviceName = device.name
            // add the device to the result list
            for (dev in scanResults!!) {
                if (dev.address == deviceAddress) return
            }
            scanResults?.add(result.device)
            // log
            statusTxt = "add scanned device: $deviceAddress"
            isStatusChange = true
            listUpdate.postValue(Event(scanResults))
        }
    }

    /**
     * BLE gattClientCallback
     */
    private val gattClientCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if( status == BluetoothGatt.GATT_FAILURE ) {
                disconnectGattServer()
                return
            } else if( status != BluetoothGatt.GATT_SUCCESS ) {
                disconnectGattServer()
                return
            }
            if( newState == BluetoothProfile.STATE_CONNECTED ) {
                // update the connection status message

                statusTxt = "Connected"
                isStatusChange = true
                Log.d(TAG, "Connected to the GATT server")
                gatt.discoverServices()
            } else if ( newState == BluetoothProfile.STATE_DISCONNECTED ) {
                disconnectGattServer()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            // check if the discovery failed
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Device service discovery failed, status: $status")
                return
            }
            // log for successful discovery
            Log.d(TAG, "Services discovery is successful")
            isConnect.postValue(Event(true))
            // find command characteristics from the GATT server
            val respCharacteristic = gatt?.let { BluetoothUtils.findResponseCharacteristic(it) }
            // disconnect if the characteristic is not found
            if( respCharacteristic == null ) {
                Log.e(TAG, "Unable to find cmd characteristic")
                disconnectGattServer()
                return
            }
            gatt.setCharacteristicNotification(respCharacteristic, true)
            // UUID for notification
            val descriptor: BluetoothGattDescriptor = respCharacteristic.getDescriptor(
                UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG)
            )
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            //Log.d(TAG, "characteristic changed: " + characteristic.uuid.toString())
            readCharacteristic(characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic written successfully")
            } else {
                Log.e(TAG, "Characteristic write unsuccessful, status: $status")
                disconnectGattServer()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic read successfully")
                readCharacteristic(characteristic)
            } else {
                Log.e(TAG, "Characteristic read unsuccessful, status: $status")
                // Trying to read from the Time Characteristic? It doesnt have the property or permissions
                // set to allow this. Normally this would be an error and you would want to:
                // disconnectGattServer()
            }
        }

        /**
         * Log the value of the characteristic
         * @param characteristic
         */

        private fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
            val msg = characteristic.getStringValue(0)

            txtRead = msg
            isTxtRead = true

            Log.d(TAG, "read: $msg")
        }


    }

    /**
     * Connect to the ble device
     */
    fun connectDevice(device: BluetoothDevice?) {
        // update the status
        statusTxt = "Connecting to ${device?.address}"
        isStatusChange = true
        bleGatt = device?.connectGatt(MyApplication.applicationContext(), false, gattClientCallback)
    }



    /**
     * Disconnect Gatt Server
     */
    fun disconnectGattServer() {
        Log.d(TAG, "Closing Gatt connection")
        // disconnect and close the gatt
        if (bleGatt != null) {
            bleGatt!!.disconnect()
            bleGatt!!.close()
            statusTxt = "Disconnected"
            isStatusChange = true
            isConnect.postValue(Event(false))
        }
    }

    fun writeData(cmdByteArray: ByteArray){
        val cmdCharacteristic = BluetoothUtils.findCommandCharacteristic(bleGatt!!)
        // disconnect if the characteristic is not found
        if (cmdCharacteristic == null) {
            Log.e(TAG, "Unable to find cmd characteristic")
            disconnectGattServer()
            return
        }

        cmdCharacteristic.value = cmdByteArray
        val success: Boolean = bleGatt!!.writeCharacteristic(cmdCharacteristic)
        // check the result
        if( !success ) {
            Log.e(TAG, "Failed to write command")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    fun startMyForeground() {
        val CHANNEL_ID = "FG0001"
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }
//        val notificationIntent = Intent(this, MainActivity::class.java);
//        val pendingIntent: PendingIntent = PendingIntent.getActivity(this,
//                 0, notificationIntent, 0);

        createServiceNotificationChannel();
//        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
////            .setContentTitle(this.getString(R.string.notification_title))
////            .setContentText(this.getString(R.string.notification_message))
////            .setSmallIcon(R.mipmap.ic_launcher)
//            .setContentTitle("hi")
////            .setContentText("hi2")
////            .setPriority(NotificationCompat.PRIORITY_HIGH)
//            .setContentIntent(pendingIntent)
//            .build()
//        startForeground(1, notification)

//        MyApplication.applicationContext().startForegroundService(Intent(MyApplication.applicationContext(), BleRepository::class.java))

        val notification: Notification = NotificationCompat.Builder(this, "NetworkServiceChannel")
            .setContentTitle("Network Service")
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1, notification)
    }
//    val VERSION_CODES = Build.VERSION_CODES()
//    val BuildInstance = Build()
//    const val VERSION_CODES: String = BuildInstance.VERSION_CODES

    fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "NetworkServiceChannel",
                "Network Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
//            val manager = getSystemService(
//                NotificationManager::class.java
//            )
            var manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)

        }
    }
}