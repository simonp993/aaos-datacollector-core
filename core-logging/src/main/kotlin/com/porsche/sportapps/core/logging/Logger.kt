package com.porsche.sportapps.core.logging

/**
 * Structured logging interface for Sport Apps.
 *
 * Privacy constraint: Signal values MUST NOT appear in log output.
 * Log structured events (state transitions, lifecycle, errors) — never raw data.
 */
interface Logger {
    fun d(
        tag: String,
        message: String,
    )

    fun i(
        tag: String,
        message: String,
    )

    fun w(
        tag: String,
        message: String,
    )

    fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    )

    fun e(
        tag: String,
        message: String,
    )

    fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    )
}
