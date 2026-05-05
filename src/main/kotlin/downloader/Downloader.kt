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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.ceil

class ChunkSizeMismatchException(downloadedSize: Int, expectedSize: Int, range: Pair<Int, Int>) :
    Exception("Size of downloaded chunk ($downloadedSize) does not match expected chunk size ($expectedSize) for range (${range.first} - ${range.second})")

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
     *
     * @return the raw byte array of the downloaded file
     */
    suspend fun downloadFile(
        fileName: String,
        serverUrl: String = defaultServerUrl,
        parallelDownloadChunks: Int = defaultParallelDownloadChunks,
        // TODO: consider if it makes sense to add configurable file info network retries and chunk fetch retries
    ) {
        require(parallelDownloadChunks > 0) {
            "Invalid number of parallel download chunks: $parallelDownloadChunks. Must be greater than 0."
        }

        require(fileName.isNotBlank()) { "File name cannot be blank!" }
        require(serverUrl.isNotBlank()) { "Server URL cannot be blank!" }

        val fileUrl = "$serverUrl/$fileName"
        val fileProperties = FileProperties.fetchFileProperties(httpClient, fileUrl)
        val contentLength = fileProperties?.contentLength ?: 0

        require(contentLength > 0) {
            "File is empty or content length could not be determined, aborting download."
        }

        if (parallelDownloadChunks == 1) {
            logger.debug { "Downloading file in a single chunk since parallelDownloadChunks is set to 1..." }
            val requestData = httpClient.get(fileUrl)
            FileChunk.writeChunk(Paths.get(fileName), 0, requestData.readRawBytes())
        } else {
            val downloadChunkSize = (contentLength.toDouble() / parallelDownloadChunks).let { ceil(it).toInt() }
            logger.debug { "Downloading $parallelDownloadChunks chunks in parallel with size $downloadChunkSize..." }

            downloadInParallel(fileUrl, contentLength, downloadChunkSize)
        }
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

    suspend fun downloadChunk(fileUrl: String, filePath: Path, range: Pair<Int, Int>) {
        val expectedChunkSize = range.second - range.first
        val chunkData = FileChunk.fetchChunk(httpClient, fileUrl, range.first, range.second)

        if (chunkData.rawBytes.size != expectedChunkSize) {
            throw ChunkSizeMismatchException(chunkData.rawBytes.size, expectedChunkSize, range)
        }

        logger.debug { "Downloaded chunk ${range.first}-${range.second} with size ${chunkData.rawBytes.size}" }

        withContext(Dispatchers.IO) {
            FileChunk.writeChunk(filePath, range.first, chunkData.rawBytes)
        }
    }

    /**
        Download the file in parallel by fetching multiple chunks concurrently using coroutines.
        Each chunk is fetched using a Range request, and all chunks are combined into a single byte array at the end.
     */
    suspend fun downloadInParallel(fileUrl: String, totalSize: Int, chunkSize: Int): Path {
        val chunkRanges = calculateChunkRanges(totalSize, chunkSize) // TODO: check if chunk size exceeds ByteArray size limit, throw ChunkTooLargeException -> write test for this too
        val filePath = Paths.get(fileUrl.split("/").last()) // TODO: make sure file exists, create it if not

        // start download of chunks in parallel (coroutines), wait for all to finish
        coroutineScope {
            chunkRanges.map { range ->
                async(Dispatchers.Default) {
                    downloadChunk(fileUrl, filePath, range)

                    // TODO: add retry logic if we get ChunkSizeMismatchException or ChunkWrongStatusCodeException, don't retry on other network failures since Ktor can handle that via retry plugin
                }
            }.awaitAll()
        }

        val downloadedSize = withContext(Dispatchers.IO) { Files.size(filePath) }
        assert(totalSize.toLong() == downloadedSize) {
            "Downloaded file size mismatch: expected $totalSize bytes, but got $downloadedSize bytes"
        }

        return filePath
    }
}