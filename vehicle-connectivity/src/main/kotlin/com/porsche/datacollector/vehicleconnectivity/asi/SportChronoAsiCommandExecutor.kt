package com.porsche.datacollector.vehicleconnectivity.asi

import com.porsche.datacollector.core.logging.Logger
import kotlinx.coroutines.withTimeout

/**
 * Real ASI command executor for the SportChronoService.
 * Translates [AsiCommand] instances to [ASISportChronoServiceClientAdapter] API calls.
 *
 * Enforces a 5-second timeout with one automatic retry.
 * Rejects commands immediately when service is not CONNECTED.
 *
 * ADR-007: ASI commands for vehicle actuations.
 */
class SportChronoAsiCommandExecutor(
    private val connector: SportChronoAsiServiceConnector,
    private val logger: Logger,
) : AsiCommandExecutor {
    override suspend fun execute(command: AsiCommand): Result<Unit> {
        if (connector.currentConnectionState != ConnectionState.CONNECTED) {
            logger.w(TAG, "Command rejected: ASI service not connected (${command.operationId})")
            return Result.failure(
                CommandRejectedException("ASI service not connected for ${command.operationId}"),
            )
        }

        return executeWithRetry(command)
    }

    private suspend fun executeWithRetry(command: AsiCommand): Result<Unit> {
        val result = executeOnce(command)
        if (result.isSuccess) return result

        logger.w(TAG, "Command ${command.operationId} failed, retrying once")
        return executeOnce(command)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeOnce(command: AsiCommand): Result<Unit> {
        val adapter =
            connector.getClientAdapter()
                ?: return Result.failure(
                    CommandRejectedException("ASI service adapter not available for ${command.operationId}"),
                )

        return try {
            withTimeout(COMMAND_TIMEOUT_MS) {
                val api = adapter.api()
                dispatchCommand(api, command)
                logger.d(TAG, "Command ${command.operationId} executed successfully")
                Result.success(Unit)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.e(TAG, "Command timeout: ${command.operationId}")
            Result.failure(
                CommandTimeoutException("Command ${command.operationId} timed out after ${COMMAND_TIMEOUT_MS}ms"),
            )
        } catch (e: Exception) {
            logger.e(TAG, "Command execution error: ${command.operationId}", e)
            Result.failure(e)
        }
    }

    private fun dispatchCommand(
        api: de.esolutions.fw.android.comm.asi.sportchronoservice.IASISportChronoServiceServiceC,
        command: AsiCommand,
    ) {
        when (command.operationId) {
            OPERATION_SET_RACE_CONDITIONING -> {
                val enabled = command.payload.isNotEmpty() && command.payload[0] == 1.toByte()
                api.setRaceConditioning(enabled)
            }

            else -> {
                throw IllegalArgumentException("Unknown operation: ${command.operationId}")
            }
        }
    }

    companion object {
        private const val TAG = "SportChronoAsiExecutor"
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val OPERATION_SET_RACE_CONDITIONING = "setRaceConditioning"
    }
}
