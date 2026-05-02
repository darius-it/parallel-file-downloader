package me.dariusit.di

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import me.dariusit.downloader.Downloader

class AppContainer (ktorHttpClientEngineFactory: HttpClientEngineFactory<HttpClientEngineConfig> = CIO) {
    val httpClient: HttpClient by lazy { HttpClient(ktorHttpClientEngineFactory) }
    val logger: KLogger by lazy { KotlinLogging.logger {} }

    fun getDownloaderInstance(): Downloader {
        return Downloader(httpClient=httpClient, logger=logger)
    }

    fun getDownloaderInstance(serverUrl: String, parallelDownloadChunks: Int): Downloader {
        return Downloader(serverUrl, parallelDownloadChunks, httpClient, logger)
    }
}