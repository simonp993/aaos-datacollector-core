package com.porsche.aaos.platform.telemetry.vehicleconnectivity.fake

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.asi.AsiCommand
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.asi.AsiCommandExecutor

class FakeAsiCommandExecutor : AsiCommandExecutor {
    private val executedCommands = mutableListOf<AsiCommand>()
    private var nextResult: Result<Unit> = Result.success(Unit)

    override suspend fun execute(command: AsiCommand): Result<Unit> {
        executedCommands.add(command)
        return nextResult
    }

    fun setNextResult(result: Result<Unit>) {
        nextResult = result
    }

    fun getExecutedCommands(): List<AsiCommand> = executedCommands.toList()

    fun clear() {
        executedCommands.clear()
        nextResult = Result.success(Unit)
    }
}
