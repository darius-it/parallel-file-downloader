package me.dariusit.downloader

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
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
        val expectedChunkSize = range.second - range.first
        httpClient.prepareGet(fileUrl) {
            headers {
                append(HttpHeaders.Range, "bytes=${range.first}-${range.second - 1}")
            }
        }.execute { response ->
            if (response.status != HttpStatusCode.PartialContent) {
                throw ChunkWrongStatusCodeException(response.status)
            }

            val channel = response.bodyAsChannel()

            withContext(Dispatchers.IO) {
                FileChannel.open(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { fileChannel ->
                    var writePosition = range.first

                    val stream = object : RawSink {
                        override fun write(source: Buffer, byteCount: Long) {
                            val temp = ByteArray(DEFAULT_BUFFER_SIZE)
                            var remaining = byteCount

                            while (remaining > 0) {
                                val toRead = minOf(temp.size.toLong(), remaining).toInt()
                                val bytesRead = source.readAtMostTo(temp, 0, toRead)
                                if (bytesRead <= 0) break

                                var offset = 0
                                while (offset < bytesRead) {
                                    val written =
                                        fileChannel.write(
                                            ByteBuffer.wrap(temp, offset, bytesRead - offset),
                                            writePosition
                                        )
                                    offset += written
                                    writePosition += written.toLong()
                                }

                                remaining -= bytesRead.toLong()
                            }
                        }

                        override fun flush() = Unit
                        override fun close() = Unit
                    }

                    val downloadedSize = channel.readTo(stream, expectedChunkSize)

                    if (downloadedSize != expectedChunkSize) {
                        throw ChunkSizeMismatchException(downloadedSize, expectedChunkSize, range)
                    }

                    logger.debug { "Downloaded chunk ${range.first}-${range.second} with size $downloadedSize" }
                }
            }
        }
    }
}