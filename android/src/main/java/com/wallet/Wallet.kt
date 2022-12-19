package com.wallet

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.ble.central.Central
import com.ble.central.ICentralListener
import com.facebook.react.bridge.Callback
import com.verifier.GattService
import com.verifier.Verifier
import java.util.*


class Wallet(context: Context, private val responseListener: (String, String) -> Unit) : ICentralListener {
  private val logTag = "Wallet"
  private var publicKey: String = "b0f8980279d4df9f383bfd6e990b45c5fcba1c4fbef76c27b9141dff50b97984"
  private var iv: String = "012345678901"
  private lateinit var advIdentifier: String;
  private var central: Central
  private val maxMTU = 517

  private enum class CentralCallbacks {
    CONNECTION_ESTABLISHED,
  }

  private val callbacks = mutableMapOf<CentralCallbacks, Callback>()

  init {
    central = Central(context, this@Wallet)
  }

  fun generateKeyPair(): String {
    return publicKey
  }

  fun startScanning(advIdentifier: String, connectionEstablishedCallback: Callback) {
    callbacks[CentralCallbacks.CONNECTION_ESTABLISHED] = connectionEstablishedCallback

    central.scan(
      Verifier.SERVICE_UUID,
      advIdentifier
    )
  }

  fun writeIdentity() {
    central.write(Verifier.SERVICE_UUID, GattService.IDENTITY_CHARACTERISTIC_UUID,"${iv}$publicKey")
  }

  override fun onScanStartedFailed(errorCode: Int) {
    Log.d(logTag, "onScanStartedFailed: $errorCode")
  }

  override fun onDeviceFound(device: BluetoothDevice, scanRecord: ScanRecord?) {
    val scanResponsePayload = scanRecord?.getServiceData(ParcelUuid(Verifier.SCAN_RESPONSE_SERVICE_UUID))?.decodeToString()
    val advertisementPayload = scanRecord?.getServiceData(ParcelUuid(Verifier.SERVICE_UUID))?.decodeToString()

    Log.d(logTag, "onDeviceFound with advPayload: ${advertisementPayload}, scanPayload: ${scanResponsePayload},")
    val firstPartOfPK = advertisementPayload?.split("_", limit = 2)?.get(1)
    val verifierPK = firstPartOfPK + scanResponsePayload

    central.stopScan()
    central.connect(device)
  }

  override fun onDeviceConnected(device: BluetoothDevice) {
    Log.d(logTag, "onDeviceConnected")

    central.discoverServices()
  }

  override fun onServicesDiscovered() {
    Log.d(logTag, "onServicesDiscovered")

    central.requestMTU(maxMTU)
  }

  override fun onServicesDiscoveryFailed(errorCode: Int) {
    Log.d(logTag, "onServicesDiscoveryFailed")
    //TODO: Handle services discovery failure
  }

  override fun onRequestMTUSuccess(mtu: Int) {
    Log.d(logTag, "onRequestMTUSuccess")
    val connectionEstablishedCallBack = callbacks[CentralCallbacks.CONNECTION_ESTABLISHED]

    connectionEstablishedCallBack?.let {
      it()
      //TODO: Why this is getting called multiple times?. (Calling callback multiple times raises a exception)
      callbacks.remove(CentralCallbacks.CONNECTION_ESTABLISHED)
    }
  }

  override fun onRequestMTUFailure(errorCode: Int) {
    //TODO: Handle onRequest MTU failure
  }

  override fun onDeviceDisconnected() {
    //TODO Handle Disconnect
  }

  override fun onWriteFailed(device: BluetoothDevice, charUUID: UUID, err: Int) {
    Log.d(logTag, "Failed to write char: $charUUID with error code: $err")
  }

  override fun onWriteSuccess(device: BluetoothDevice, charUUID: UUID) {
    Log.d(logTag, "Wrote to $charUUID successfully")

    responseListener("exchange-receiver-info", "{\"deviceName\": \"Verifier dummy\"}")
  }

  fun setAdvIdentifier(advIdentifier: String) {
    this.advIdentifier = advIdentifier
  }

}
