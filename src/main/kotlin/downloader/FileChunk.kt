package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ChunkWrongStatusCodeException(statusCode: HttpStatusCode) :
    Exception("Failed to fetch file chunk (received unexpected HTTP status code $statusCode)!")

data class FileChunk (
    val start: Int,
    val end: Int,
    val rawBytes: ByteArray
) {
    override fun toString(): String {
        return "bytes=$start-${end - 1}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileChunk

        if (start != other.start) return false
        if (end != other.end) return false
        if (!rawBytes.contentEquals(other.rawBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + end.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        return result
    }

    companion object {
        /**
         * Fetch a specific chunk of a file via HTTP Range request.
         *
         * @param httpClient the Ktor HTTP client to use for the request
         * @param fileName the URL of the file to fetch from
         * @param start the starting byte position (inclusive)
         * @param end the ending byte position (exclusive)
         * @return a FileChunk containing the bytes between start and end
         * @throws ChunkWrongStatusCodeException if response status is not PartialContent (206)
         */
        suspend fun fetchChunk(httpClient: HttpClient, fileName: String, start: Int, end: Int): FileChunk {
            val response = httpClient.get(fileName) {
                headers {
                    append("Range", "bytes=${start}-${end - 1}")
                }
            }

            if (response.status != HttpStatusCode.PartialContent) {
                throw ChunkWrongStatusCodeException(response.status)
            }

            val rawBytes = response.readRawBytes()

            return FileChunk(start, end, rawBytes)
        }

        /**
         * Write raw byte data to a file at a specific byte position using FileChannel for random access.
         *
         * @param filePath the destination file path
         * @param position the byte offset where data should be written
         * @param data the byte array to write
         */
        fun writeChunk(filePath: Path, position: Int, data: ByteArray) {
            FileChannel.open(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(data)
                channel.write(buffer, position.toLong())
            }
        }
    }
}