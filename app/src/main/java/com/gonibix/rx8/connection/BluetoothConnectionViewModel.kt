package com.gonibix.rx8.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.obd.Elm327Session
import com.gonibix.rx8.obd.ObdSessionFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Compatibility facade for standalone callers/tests; app sessions belong to BluetoothSessionService. */
class BluetoothConnectionViewModel(
    gateway: BluetoothGateway,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    obdSessionFactory: ObdSessionFactory = ObdSessionFactory { Elm327Session(it) },
) : ViewModel() {
    private val controller = BluetoothSessionController(gateway, viewModelScope, dispatcher, obdSessionFactory)
    val uiState = controller.uiState

    fun refresh() = controller.refresh()
    fun setForeground(isForeground: Boolean) = controller.setForeground(isForeground)
    fun startDiscovery() = controller.startDiscovery()
    fun stopDiscovery() = controller.stopDiscovery()
    fun selectDevice(device: AdapterDevice) = controller.selectDevice(device)
    fun connect() = controller.connect()
    fun disconnect() = controller.disconnect()
    fun startObd() = controller.startObd()
    fun readVin() = controller.readVin()
    fun onDeviceDisconnected(address: String) = controller.onDeviceDisconnected(address)

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
