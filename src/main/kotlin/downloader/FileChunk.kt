package me.dariusit.downloader

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ChunkWrongStatusCodeException(statusCode: HttpStatusCode) :
    Exception("Failed to fetch file chunk (received unexpected HTTP status code $statusCode)!")

object FileChunk {
    /**
     * Download a single chunk of a file and write it to disk at the specified byte position.
     *
     * @param httpClient Ktor HTTP client used to download chunk
     * @param logger logger object (KotlinLogging)
     * @param fileUrl the full URL to download from
     * @param filePath the destination file path
     * @param range a Pair of (start, end) byte indices for this chunk
     * @throws ChunkSizeMismatchException if downloaded chunk size doesn't match expected size
     * @throws ChunkWrongStatusCodeException if the HTTP response status code is not PartialContent
     */
    suspend fun downloadChunk(
        httpClient: HttpClient,
        logger: KLogger,
        fileUrl: String,
        filePath: Path,
        range: Pair<Long, Long>
    ) {
        val file = filePath.toFile()
        val stream = file.outputStream().asSink()
        val bufferSize: Long = 1024 * 1024

        val expectedChunkSize = range.second - range.first
        httpClient.prepareGet(fileUrl) {
            headers {
                append(HttpHeaders.Range, "bytes=${range.first}-${range.second - 1}")
            }
        }.execute { response ->
            if (response.status != HttpStatusCode.PartialContent) {
                throw ChunkWrongStatusCodeException(response.status)
            }

            val channel: ByteReadChannel = response.body()

            withContext(Dispatchers.IO) {
                FileChannel.open(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { fileChannel ->
                    fileChannel.position(range.first)

                    var totalWritten = 0L
                    val buffer = ByteBuffer.allocateDirect(1024 * 64) // 64KB buffer

                    while (!channel.isClosedForRead) {
                        buffer.clear()
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break

                        buffer.flip()
                        while (buffer.hasRemaining()) {
                            totalWritten += fileChannel.write(buffer)
                        }
                    }
                    logger.debug { "Finished chunk: $totalWritten bytes written." }
                }
            }
        }
    }
}