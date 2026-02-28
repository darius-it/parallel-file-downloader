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

    suspend fun downloadFile(fileName: String, saveToDisk: Boolean = true) {
        val fileUrl = "$SERVER_URL/$fileName"
        val fileProperties = fetchFileProperties(client, fileUrl)
        val contentLength = fileProperties?.contentLength ?: 0

        println("Content length of file: ${fileProperties?.contentLength}")

        if (contentLength <= 0) {
            println("File is empty or content length could not be determined, aborting download.")
            return
        }

        val downloadChunkSize = (contentLength.toDouble() / PARALLEL_DOWNLOAD_CHUNKS).let { ceil(it).toInt() };
        println("Downloading $PARALLEL_DOWNLOAD_CHUNKS chunks in parallel with size $downloadChunkSize...")

        val rawData = downloadInParallel(fileUrl, contentLength, downloadChunkSize)

        if (saveToDisk)
            File(fileName).writeBytes(rawData)
    }

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

    suspend fun downloadInParallel(fileName: String, totalSize: Int, chunkSize: Int): ByteArray {
        // get all chunk boundaries
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