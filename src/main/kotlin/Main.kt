package me.dariusit

import me.dariusit.di.AppContainer

suspend fun main() {
    val container = AppContainer()
    val downloader = container.getDownloaderInstance()

    downloader.downloadFile("mastodon.png")
}