package me.dariusit

import me.dariusit.downloader.Downloader.downloadFile

suspend fun main() {
    downloadFile("mastodon.svg")
}