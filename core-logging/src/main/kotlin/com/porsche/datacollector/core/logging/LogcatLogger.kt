package com.porsche.datacollector.core.logging

import android.util.Log

/**
 * Production [Logger] implementation that writes to Android Logcat
 * with the `[SportApps:<tag>]` format.
 */
class LogcatLogger : Logger {
    override fun d(
        tag: String,
        message: String,
    ) {
        Log.d(format(tag), message)
    }

    override fun i(
        tag: String,
        message: String,
    ) {
        Log.i(format(tag), message)
    }

    override fun w(
        tag: String,
        message: String,
    ) {
        Log.w(format(tag), message)
    }

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        Log.w(format(tag), message, throwable)
    }

    override fun e(
        tag: String,
        message: String,
    ) {
        Log.e(format(tag), message)
    }

    override fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        Log.e(format(tag), message, throwable)
    }

    internal fun format(tag: String): String = "[DataCollector:$tag]"
}
