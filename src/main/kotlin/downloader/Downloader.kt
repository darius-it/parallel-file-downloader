package me.dariusit.downloader

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.dariusit.downloader.FileProperties.Companion.fetchFileProperties
import java.io.File
import kotlin.math.ceil

object Downloader {
    private var SERVER_URL = "http://localhost:8080"
    private var PARALLEL_DOWNLOAD_CHUNKS = 2

    var client: HttpClient = HttpClient(CIO)

    private val logger = KotlinLogging.logger {}

    /**
     * Fetch file properties and download in parallel
     *
     * @param fileName the name of the file to download (e.g. "mastodon.svg")
     * @param serverUrl the base URL of the server to download from (default: "http://localhost:8080")
     * @param parallelDownloadChunks the number of chunks to download in parallel (default: 2)
     * @param saveToDisk whether to save the downloaded file to disk (default: true)
     *
     * @return the raw byte array of the downloaded file
     */
    suspend fun downloadFile(
        fileName: String,
        serverUrl: String = SERVER_URL,
        parallelDownloadChunks: Int = PARALLEL_DOWNLOAD_CHUNKS,
        saveToDisk: Boolean = true
    ): ByteArray {
        require(parallelDownloadChunks > 0) {
            "Invalid number of parallel download chunks: $parallelDownloadChunks. Must be greater than 0."
        }

        require(fileName.isNotBlank()) { "File name cannot be blank!" }
        require(serverUrl.isNotBlank()) { "Server URL cannot be blank!" }

        val fileUrl = "$serverUrl/$fileName"
        val fileProperties = fetchFileProperties(client, fileUrl)
        val contentLength = fileProperties?.contentLength ?: 0

        require(contentLength > 0) {
            "File is empty or content length could not be determined, aborting download."
        }

        var rawData: ByteArray?

        if (parallelDownloadChunks == 1) {
            logger.debug { "Downloading file in a single chunk since parallelDownloadChunks is set to 1..." }
            val requestData = client.get(fileUrl)
            rawData = requestData.readRawBytes()
        } else {
            val downloadChunkSize = (contentLength.toDouble() / parallelDownloadChunks).let { ceil(it).toInt() }
            logger.debug { "Downloading $parallelDownloadChunks chunks in parallel with size $downloadChunkSize..." }

            rawData = downloadInParallel(fileUrl, contentLength, downloadChunkSize)
        }

        if (saveToDisk)
            File(fileName).writeBytes(rawData)

        return rawData
    }

    /**
     * Calculate the byte ranges for each chunk based on the total file size and intended chunk size
     *
     * @return Pairs of (start, end) byte indices, e.g. (0, 500), (500, 1000), etc.
     */
    fun calculateChunkRanges(totalSize: Int, chunkSize: Int): List<Pair<Int, Int>> {
        require(totalSize > 0) { "Total file size must be greater than 0!" }
        require(chunkSize > 0) { "Chunk size must be greater than 0!" }

        val chunkRanges = mutableListOf<Pair<Int, Int>>()
        var currentStart = 0

        while (currentStart < totalSize) {
            val currentEnd = minOf(currentStart + chunkSize, totalSize)
            chunkRanges.add(Pair(currentStart, currentEnd))
            currentStart = currentEnd
        }

        println(chunkRanges)

        return chunkRanges
    }

    /**
    Download the file in parallel by fetching multiple chunks concurrently using coroutines.
    Each chunk is fetched using a Range request, and all chunks are combined into a single byte array at the end.
     */
    suspend fun downloadInParallel(fileName: String, totalSize: Int, chunkSize: Int): ByteArray {
        val chunkRanges = calculateChunkRanges(totalSize, chunkSize)

        // start download of chunks in parallel (coroutines), wait for all to finish
        val chunks = coroutineScope {
            chunkRanges.map { range ->
                async(Dispatchers.Default) {
                    val chunkData = FileChunk.fetchChunk(client, fileName, range.first, range.second)
                    val expectedChunkSize = range.second - range.first

                    if (chunkData.rawBytes.size != expectedChunkSize) {
                        throw Exception("Chunk size mismatch! Expected $expectedChunkSize bytes, but got ${chunkData.rawBytes.size} bytes for range ${range.first}-${range.second}")
                    }

                    logger.debug { "Downloaded chunk ${range.first}-${range.second} with size ${chunkData.rawBytes.size}" }
                    chunkData.rawBytes
                }
            }.awaitAll()
        }

        // combine all chunks into one byte array
        val combinedData = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(combinedData, destinationOffset = offset)
            offset += chunk.size
        }
        require(combinedData.size == totalSize) { "Combined data size does not match total size!" }

        return combinedData
    }
}