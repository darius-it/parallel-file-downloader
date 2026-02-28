package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode

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
        suspend fun fetchChunk(httpClient: HttpClient, fileName: String, start: Int, end: Int): FileChunk {
            val response = httpClient.get(fileName) {
                headers {
                    append("Range", "bytes=${start}-${end - 1}")
                }
            }

            if (response.status != HttpStatusCode.PartialContent) {
                println("Something went wrong when getting the chunk! Status code: ${response.status}")
                return FileChunk(start, end, ByteArray(0))
            }

            val rawBytes = response.readRawBytes()

            return FileChunk(start, end, rawBytes)
        }
    }
}