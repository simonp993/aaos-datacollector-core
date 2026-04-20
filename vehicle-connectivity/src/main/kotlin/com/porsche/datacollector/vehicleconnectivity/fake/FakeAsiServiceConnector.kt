package com.porsche.datacollector.vehicleconnectivity.fake

import com.porsche.datacollector.vehicleconnectivity.asi.AsiServiceConnector
import com.porsche.datacollector.vehicleconnectivity.asi.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAsiServiceConnector : AsiServiceConnector {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    override fun connect() {
        _connectionState.value = ConnectionState.CONNECTED
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun observeConnectionState(): Flow<ConnectionState> = _connectionState.asStateFlow()

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
