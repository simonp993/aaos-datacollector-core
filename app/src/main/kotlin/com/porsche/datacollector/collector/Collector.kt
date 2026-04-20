package com.porsche.datacollector.collector

interface Collector {
    val name: String

    suspend fun start()

    fun stop()
}
