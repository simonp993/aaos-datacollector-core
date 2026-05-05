package com.porsche.aaos.platform.telemetry.core.testing

import com.porsche.aaos.platform.telemetry.core.logging.Logger

object NoOpLogger : Logger {
    override fun d(
        tag: String,
        message: String,
    ) = Unit

    override fun i(
        tag: String,
        message: String,
    ) = Unit

    override fun w(
        tag: String,
        message: String,
    ) = Unit

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = Unit

    override fun e(
        tag: String,
        message: String,
    ) = Unit

    override fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = Unit
}
