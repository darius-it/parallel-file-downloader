package me.dariusit.downloader

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ChunkSizeMismatchException(downloadedSize: Int, expectedSize: Int, range: Pair<Int, Int>) :
    Exception("Size of downloaded chunk ($downloadedSize) does not match expected chunk size ($expectedSize) for range (${range.first} - ${range.second})")

class ChunkTooLargeException: Exception("Chunk size exceeds max size of Java (Byte)Array! Please increase the number of parallelDownloadChunks.")

class Downloader (
    private val defaultServerUrl: String = "http://localhost:8080",
    private val parallelChunkAmount: Int = 2,
    private val chunkFailureRetries: Int = 3,
    private val httpClient: HttpClient,
    private val logger: KLogger
) {

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
        parallelDownloadChunks: Int = parallelChunkAmount,
        // TODO: consider if it makes sense to add configurable file info network retries and chunk fetch retries
    ) {
        logger.debug { "Fetching file properties for $fileName..." }
        val fileProperties = getFileProperties(fileName, serverUrl, parallelDownloadChunks)
        val chunkRanges = calculateChunkRanges(fileProperties.contentLength, parallelDownloadChunks)

        logger.debug { "Downloading ${chunkRanges.size} chunks in parallel..." }
        val destinationFilePath = getDestinationFilePath(fileName)
        val downloadUrl = "$serverUrl/$fileName"
        downloadInParallel(downloadUrl, destinationFilePath, chunkRanges, fileProperties.contentLength)
    }

    /**
     * Stage 1 of download process: use HEAD request to get information about file to download (total size, does it accept byte ranges, etc.)
     */
    suspend fun getFileProperties(
        fileName: String,
        serverUrl: String = defaultServerUrl,
        parallelDownloadChunks: Int = parallelChunkAmount
    ): FileProperties {
        require(parallelDownloadChunks > 0) {
            "Invalid number of parallel download chunks: $parallelDownloadChunks. Must be greater than 0."
        }

        require(fileName.isNotBlank()) { "File name cannot be blank!" }
        require(serverUrl.isNotBlank()) { "Server URL cannot be blank!" }

        val fileUrl = "$serverUrl/$fileName"
        val fileProperties = FileProperties.fetchFileProperties(httpClient, fileUrl)

        require(fileProperties.contentLength > 0) {
            "File is empty or content length could not be determined, aborting download."
        }

        return fileProperties
    }

    /**
     * Calculate the byte ranges for each chunk based on the total file size and intended chunk size
     *
     * @return Pairs of (start, end) byte indices, e.g. (0, 500), (500, 1000), etc.
     */
    fun calculateChunkRanges(totalSize: Int, parallelDownloadChunks: Int): List<Pair<Int, Int>> {
        require(totalSize > 0) { "Total file size must be greater than 0!" }
        require(parallelDownloadChunks > 0) { "parallelDownloadChunks must be greater than 0!" }

        val chunkCount = minOf(totalSize, parallelDownloadChunks)
        val baseChunkSize = totalSize / chunkCount
        val remainder = totalSize % chunkCount

        if (baseChunkSize >= Int.MAX_VALUE) {
            throw ChunkTooLargeException()
        }

        val chunkRanges = mutableListOf<Pair<Int, Int>>()
        var currentStart = 0

        repeat(chunkCount) { index ->
            val currentChunkSize = baseChunkSize + if (index < remainder) 1 else 0
            val currentEnd = currentStart + currentChunkSize
            chunkRanges.add(currentStart to currentEnd)
            currentStart = currentEnd
        }

        logger.debug{ "Determined chunk ranges for download: $chunkRanges" }

        return chunkRanges
    }

    fun getDestinationFilePath(fileName: String): Path {
        val filePath = Paths.get(fileName)
        return if (Files.notExists(filePath)) Files.createFile(filePath) else filePath
    }

    /**
     *  Stage 2 of download process: go over each numerical chunk range, download it and save it to disk.
        Multiple chunks are fetched concurrently using coroutines.
        Each chunk is fetched using a Range request, and all chunks are combined into a single byte array at the end.
     */
    suspend fun downloadInParallel(
        downloadUrl: String,
        destinationFilePath: Path,
        chunkRanges: List<Pair<Int, Int>>,
        totalFileSize: Int
    ) {
        // start download of chunks in parallel (coroutines), wait for all to finish
        coroutineScope {
            chunkRanges.map { range ->
                async(Dispatchers.Default) {
                    tryCatchWithRetry {
                        downloadChunk(downloadUrl, destinationFilePath, range)
                    }
                }
            }.awaitAll()
        }

        val downloadedSize = withContext(Dispatchers.IO) { Files.size(destinationFilePath) }
        assert(totalFileSize.toLong() == downloadedSize) {
            "Downloaded file size mismatch: expected $totalFileSize bytes, but got $downloadedSize bytes"
        }
    }

    /**
     * Helper for downloadInParallel, downloads the contents of one singular chunk and saves them into our file on disk
     */
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
     * Retry `func` up to `maxRetries` times only when the thrown exception is one of [retryOn].
     * Any other exception is rethrown immediately.
     *
     * @param maxRetries number of retries in addition to the initial attempt (>= 0)
     * @param retryOn array of Exception classes that are considered retryable.
     */
    suspend fun tryCatchWithRetry(
        maxRetries: Int = chunkFailureRetries,
        retryOn: Array<Class<out Exception>> = arrayOf(ChunkSizeMismatchException::class.java,
            ChunkWrongStatusCodeException::class.java),
        func: suspend () -> Unit
    ) {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }

        var retries = 0

        while (true) {
            try {
                func()
                return
            } catch (e: Exception) {
                val isRetryable = retryOn.any { it.isInstance(e) }
                if (!isRetryable) throw e // throw immediately if we hit exception other than accepted ones

                if (retries >= maxRetries) throw e

                retries++
            }
        }
    }
}