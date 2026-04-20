package com.porsche.sportapps.vehicleconnectivity.asi

import kotlinx.coroutines.flow.Flow

interface AsiServiceConnector {
    fun connect()

    fun disconnect()

    fun observeConnectionState(): Flow<ConnectionState>
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
