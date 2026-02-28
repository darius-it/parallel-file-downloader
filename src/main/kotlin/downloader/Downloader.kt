package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.dariusit.downloader.FileProperties.Companion.fetchFileProperties
import java.io.File
import kotlin.math.ceil

object Downloader {
    private var SERVER_URL = "http://localhost:8080";
    private var PARALLEL_DOWNLOAD_CHUNKS = 2;

    private var client: HttpClient = HttpClient(CIO)

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
    ): ByteArray? {
        val fileUrl = "$serverUrl/$fileName"
        val fileProperties = fetchFileProperties(client, fileUrl)
        val contentLength = fileProperties?.contentLength ?: 0

        println("Content length of file: ${fileProperties?.contentLength}")

        if (contentLength <= 0) {
            println("File is empty or content length could not be determined, aborting download.")
            return null
        }

        val downloadChunkSize = (contentLength.toDouble() / parallelDownloadChunks).let { ceil(it).toInt() };
        println("Downloading $parallelDownloadChunks chunks in parallel with size $downloadChunkSize...")

        val rawData = downloadInParallel(fileUrl, contentLength, downloadChunkSize)

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
        val chunkRanges = mutableListOf<Pair<Int, Int>>()
        var currentStart = 0

        while (currentStart < totalSize) {
            val currentEnd = minOf(currentStart + chunkSize, totalSize)
            chunkRanges.add(Pair(currentStart, currentEnd))
            currentStart = currentEnd
        }

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

                    println("Downloaded chunk ${range.first}-${range.second} with size ${chunkData.rawBytes.size}")
                    chunkData.rawBytes
                }
            }.awaitAll()
        }

        // combine all chunks into one byte array
        val combinedData = chunks.reduce { acc, chunk -> acc + chunk }
        assert(combinedData.size == totalSize) { "Combined data size does not match total size!" }

        return combinedData;
    }
}