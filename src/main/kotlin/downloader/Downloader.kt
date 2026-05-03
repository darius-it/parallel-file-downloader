package me.dariusit.downloader

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.dariusit.downloader.FileProperties.Companion.fetchFileProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.ceil

class Downloader (
    serverUrl: String = "http://localhost:8080",
    parallelDownloadChunks: Int = 2,
    private val httpClient: HttpClient,
    private val logger: KLogger
) {
    private val defaultServerUrl = serverUrl
    private val defaultParallelDownloadChunks = parallelDownloadChunks

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
        serverUrl: String = defaultServerUrl,
        parallelDownloadChunks: Int = defaultParallelDownloadChunks,
        saveToDisk: Boolean = true
    ) {
        require(parallelDownloadChunks > 0) {
            "Invalid number of parallel download chunks: $parallelDownloadChunks. Must be greater than 0."
        }

        require(fileName.isNotBlank()) { "File name cannot be blank!" }
        require(serverUrl.isNotBlank()) { "Server URL cannot be blank!" }

        val fileUrl = "$serverUrl/$fileName"
        val fileProperties = fetchFileProperties(httpClient, fileUrl)
        val contentLength = fileProperties?.contentLength ?: 0

        require(contentLength > 0) {
            "File is empty or content length could not be determined, aborting download."
        }

        if (parallelDownloadChunks == 1) {
            logger.debug { "Downloading file in a single chunk since parallelDownloadChunks is set to 1..." }
            val requestData = httpClient.get(fileUrl)
            writeChunk(File(fileName), 0, requestData.readRawBytes())
        } else {
            val downloadChunkSize = (contentLength.toDouble() / parallelDownloadChunks).let { ceil(it).toInt() }
            logger.debug { "Downloading $parallelDownloadChunks chunks in parallel with size $downloadChunkSize..." }

            downloadInParallel(fileUrl, contentLength, downloadChunkSize)
        }

        // TODO: add back in-memory fallback if downloaded file doesn't exceed ByteArray size limit
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

        logger.debug{ "Determined chunk ranges for download: $chunkRanges" }

        return chunkRanges
    }

    /*
        Given a file object, opens a FileChannel to stream one individual chunk of data to disk, starting at a specified position/offset.
     */
    fun writeChunk(file: File, position: Int, data: ByteArray) {
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(data)
            channel.write(buffer, position.toLong())
        }
    }

    /**
    Download the file in parallel by fetching multiple chunks concurrently using coroutines.
    Each chunk is fetched using a Range request, and all chunks are combined into a single byte array at the end.
     */
    suspend fun downloadInParallel(fileName: String, totalSize: Int, chunkSize: Int) {
        val chunkRanges = calculateChunkRanges(totalSize, chunkSize)
        val file = File(fileName.split("/").last())

        // start download of chunks in parallel (coroutines), wait for all to finish
        val chunks = coroutineScope {
            chunkRanges.map { range ->
                async(Dispatchers.Default) {
                    val chunkData = FileChunk.fetchChunk(httpClient, fileName, range.first, range.second)
                    val expectedChunkSize = range.second - range.first

                    if (chunkData.rawBytes.size != expectedChunkSize) {
                        throw Exception("Chunk size mismatch! Expected $expectedChunkSize bytes, but got ${chunkData.rawBytes.size} bytes for range ${range.first}-${range.second}")
                    }

                    logger.debug { "Downloaded chunk ${range.first}-${range.second} with size ${chunkData.rawBytes.size}" }

                    withContext(Dispatchers.IO) {
                        writeChunk(file, range.first, chunkData.rawBytes)
                    }
                }
            }.awaitAll()
        }

        // TODO: cleanup, add in-memory fallback (maybe list of ByteArrays or hard cap that max in mem size is one size of ByteArray)
    }
}