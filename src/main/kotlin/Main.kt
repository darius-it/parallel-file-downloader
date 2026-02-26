package me.dariusit

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import kotlin.math.ceil

private var SERVER_URL = "http://localhost:8080";
private var PARALLEL_DOWNLOAD_CHUNKS = 2;

private var client: HttpClient = HttpClient(CIO)

suspend fun main() {
    val contentLength = getContentLength("mastodon.svg")
    println("Content length of file: $contentLength")

    if (contentLength != null && contentLength > 0) {
        val downloadChunkSize = ceil((contentLength / PARALLEL_DOWNLOAD_CHUNKS));
        println("Downloading $PARALLEL_DOWNLOAD_CHUNKS chunks in parallel with size $downloadChunkSize...")
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

suspend fun downloadInParallel(fileName: String, totalSize: Int, chunkSize: Int) {
    // get all chunk boundaries


    // start download of chunks in parallel (coroutines)


    // wait for all chunks to finish downloading, combine them


    /*
        Potential test cases:
        - check if combined file == original file
        - test edge cases like downloading empty file
        - test edge cases with decimal chunk sizes .5 etc. -> does the last chunk get downloaded correctly
        - etc.
     */

}