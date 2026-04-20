package com.porsche.datacollector.vehicleconnectivity.asi

interface AsiCommandExecutor {
    suspend fun execute(command: AsiCommand): Result<Unit>
}

data class AsiCommand(
    val serviceId: String,
    val operationId: String,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AsiCommand) return false
        return serviceId == other.serviceId &&
            operationId == other.operationId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = serviceId.hashCode()
        result = 31 * result + operationId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

class CommandTimeoutException(
    message: String,
) : Exception(message)

class CommandRejectedException(
    message: String,
) : Exception(message)
