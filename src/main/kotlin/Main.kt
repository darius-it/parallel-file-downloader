package me.dariusit

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.readBytes
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private var SERVER_URL = "http://localhost:8080";
private var PARALLEL_DOWNLOAD_CHUNKS = 2;

private var client: HttpClient = HttpClient(CIO)

suspend fun main() {
    val contentLength = getContentLength("mastodon.svg")
    println("Content length of file: $contentLength")

    if (contentLength != null && contentLength > 0) {
        val downloadChunkSize = ceil(contentLength / PARALLEL_DOWNLOAD_CHUNKS);
        println("Downloading $PARALLEL_DOWNLOAD_CHUNKS chunks in parallel with size $downloadChunkSize...")

        val rawData = downloadInParallel("mastodon.svg", contentLength, downloadChunkSize)

        // download raw data to file
        java.io.File("downloaded_mastodon.svg").writeBytes(rawData)
    }
}

suspend fun getContentLength(fileName: String):  Double? {
    val response = client.head("$SERVER_URL/$fileName")

    if (response.status != HttpStatusCode.OK) {
        println("Something went wrong when getting the request")
        // do some better error handling here!
        return null;
    }

    return response.headers["content-length"]?.toDouble();
}

suspend fun downloadInParallel(fileName: String, totalSize: Double, chunkSize: Double): ByteArray {
    // get all chunk boundaries
    val chunkRanges = mutableListOf<Pair<Double, Double>>()
    var currentStart = 0.0
    while (currentStart < totalSize) {
        val currentEnd = minOf(currentStart + chunkSize, totalSize)
        chunkRanges.add(Pair(currentStart, currentEnd))
        currentStart = currentEnd
    }

    // start download of chunks in parallel (coroutines), wait for all to finish
    val chunks = coroutineScope {
        chunkRanges.map { range ->
            async(Dispatchers.Default) {
                val chunkData = downloadChunk(fileName, range.first, range.second)
                println("Downloaded chunk ${range.first}-${range.second} with size ${chunkData.size}")
                chunkData
            }
        }.awaitAll()
    }

    // combine all chunks into one byte array
    val combinedData = chunks.reduce { acc, chunk -> acc + chunk }

    println(combinedData.size)

    assert(combinedData.size == totalSize.toInt()) { "Combined data size does not match total size!" }

    return combinedData;
    /*
        Potential test cases:
        - check if combined file == original file
        - test edge cases like downloading empty file
        - test edge cases with decimal chunk sizes .5 etc. -> does the last chunk get downloaded correctly
        - etc.
     */
}

suspend fun downloadChunk(fileName: String, start: Double, end: Double): ByteArray {
    // make http request with range header to download chunk
    val response = client.get("$SERVER_URL/$fileName") {
        headers {
            append("Range", "bytes=${start.toLong()}-${end.toLong() - 1}")
        }
    }

    if (response.status != HttpStatusCode.PartialContent) {
        println("Something went wrong when downloading chunk $start-$end")
        // do some better error handling here!
        return ByteArray(0);
    }

    return response.readRawBytes()
}