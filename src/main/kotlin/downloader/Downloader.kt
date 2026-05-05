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

class ChunkSizeMismatchException(downloadedSize: Long, expectedSize: Long, range: Pair<Long, Long>) :
    Exception("Size of downloaded chunk ($downloadedSize) does not match expected chunk size ($expectedSize) for range (${range.first} - ${range.second})")

class ChunkTooLargeException:
    Exception("Chunk size exceeds max size of Java (Byte)Array! Please increase the number of parallelDownloadChunks.")

class Downloader (
    private val defaultServerUrl: String = "http://localhost:8080",
    private val parallelChunkAmount: Int = 2,
    private val chunkFailureRetries: Int = 3,
    private val httpClient: HttpClient,
    private val logger: KLogger
) {

    /**
     * Fetch file properties and download a file in parallel chunks, streaming each chunk directly to disk.
     *
     * @param fileName the name of the file to download (e.g. "mastodon.svg")
     * @param serverUrl the base URL of the server to download from (default: "http://localhost:8080")
     * @param parallelDownloadChunks the number of chunks to download in parallel (default: 2)
     * @throws ChunkTooLargeException if a single chunk exceeds Int.MAX_VALUE
     * @throws IllegalArgumentException if fileName, serverUrl, or parallelDownloadChunks are invalid
     * @throws AssertionError if final downloaded file size doesn't match expected total size
     */
    suspend fun downloadFile(
        fileName: String,
        serverUrl: String = defaultServerUrl,
        parallelDownloadChunks: Int = parallelChunkAmount,
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
     *
     * @param fileName the name of the file to query
     * @param serverUrl the base URL of the server
     * @param parallelDownloadChunks the intended number of parallel chunks (used for validation)
     * @return FileProperties containing the content length and accept-ranges header
     * @throws IllegalArgumentException if any parameter is invalid
     * @throws FilePropertiesWrongStatusCodeException if HEAD request returns unexpected status code
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

        require(fileProperties.contentLength > 0L) {
            "File is empty or content length could not be determined, aborting download."
        }

        return fileProperties
    }

    /**
     * Calculate the byte ranges for each chunk based on the total file size and number of parallel chunks.
     * Distributes remainder bytes among the first chunks to ensure even distribution.
     *
     * @param totalSize the total size of the file in bytes
     * @param parallelDownloadChunks the number of chunks to divide the file into
     * @return a list of Pairs containing (start, end) byte indices, e.g. [(0, 500), (500, 1000), ...]
     * @throws IllegalArgumentException if totalSize or parallelDownloadChunks is <= 0
     * @throws ChunkTooLargeException if a single chunk would exceed Int.MAX_VALUE
     */
    fun calculateChunkRanges(totalSize: Long, parallelDownloadChunks: Int): List<Pair<Long, Long>> {
        require(totalSize > 0L) { "Total file size must be greater than 0!" }
        require(parallelDownloadChunks > 0) { "parallelDownloadChunks must be greater than 0!" }

        val chunkCount = minOf(totalSize, parallelDownloadChunks.toLong())
        val baseChunkSize = totalSize / chunkCount
        val remainder = totalSize % chunkCount

        if (baseChunkSize > Int.MAX_VALUE.toLong() || (baseChunkSize == Int.MAX_VALUE.toLong() && remainder > 0L)) {
            throw ChunkTooLargeException()
        }

        val chunkRanges = mutableListOf<Pair<Long, Long>>()
        var currentStart = 0L

        repeat(chunkCount.toInt()) { index ->
            val currentChunkSize = baseChunkSize + if (index.toLong() < remainder) 1L else 0L
            val currentEnd = currentStart + currentChunkSize
            chunkRanges.add(currentStart to currentEnd)
            currentStart = currentEnd
        }

        logger.debug{ "Determined chunk ranges for download: $chunkRanges" }

        return chunkRanges
    }

    /**
     * Get the destination file path, creating the file if it doesn't exist.
     *
     * @param fileName the name of the file to create
     * @return the Path object pointing to the file
     */
    fun getDestinationFilePath(fileName: String): Path {
        val filePath = Paths.get(fileName)
        return if (Files.notExists(filePath)) Files.createFile(filePath) else filePath
    }

    /**
     * Stage 2 of download process: download all chunks concurrently via Range requests and stream each chunk directly to disk.
     * All chunks are downloaded in parallel using coroutines, and each chunk is written to its designated position in the file.
     *
     * @param downloadUrl the full URL to download from
     * @param destinationFilePath the Path where chunks will be written
     * @param chunkRanges list of (start, end) byte range pairs to download
     * @param totalFileSize the expected total size of the downloaded file for validation
     * @throws AssertionError if the final file size doesn't match the expected total size
     * @throws Exception if any chunk download or write fails (and retries are exhausted)
     */
    suspend fun downloadInParallel(
        downloadUrl: String,
        destinationFilePath: Path,
        chunkRanges: List<Pair<Long, Long>>,
        totalFileSize: Long
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
        assert(totalFileSize == downloadedSize) {
            "Downloaded file size mismatch: expected $totalFileSize bytes, but got $downloadedSize bytes"
        }
    }

    /**
     * Download a single chunk of a file and write it to disk at the specified byte position.
     *
     * @param fileUrl the full URL to download from
     * @param filePath the destination file path
     * @param range a Pair of (start, end) byte indices for this chunk
     * @throws ChunkSizeMismatchException if downloaded chunk size doesn't match expected size
     * @throws ChunkWrongStatusCodeException if the HTTP response status code is not PartialContent
     */
    suspend fun downloadChunk(fileUrl: String, filePath: Path, range: Pair<Long, Long>) {
        val expectedChunkSize = range.second - range.first
        val chunkData = FileChunk.fetchChunk(httpClient, fileUrl, range.first, range.second)

        if (chunkData.rawBytes.size.toLong() != expectedChunkSize) {
            throw ChunkSizeMismatchException(chunkData.rawBytes.size.toLong(), expectedChunkSize, range)
        }

        logger.debug { "Downloaded chunk ${range.first}-${range.second} with size ${chunkData.rawBytes.size}" }

        withContext(Dispatchers.IO) {
            FileChunk.writeChunk(filePath, range.first, chunkData.rawBytes)
        }
    }

    /**
     * Retry a suspend function multiple times if it throws specific retryable exceptions.
     * Non-retryable exceptions are rethrown immediately.
     *
     * @param maxRetries number of retries in addition to the initial attempt (>= 0)
     * @param retryOn array of Exception classes that should trigger a retry
     * @param func the suspend lambda to execute and retry on failure
     * @throws Exception the last caught exception if all retries are exhausted, or immediately if non-retryable
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