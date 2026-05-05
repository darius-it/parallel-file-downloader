package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode

class FilePropertiesWrongStatusCodeException(statusCode: HttpStatusCode) :
    Exception("Failed to get file information via HEAD request (received unexpected HTTP status code $statusCode)!")

data class FileProperties (val contentLength: Int, val acceptRanges: String? = null) {
    // TODO: see if we can easily pull checksum from file server without downloading entire file
    // Idea: add checksum files like for Linux ISOs and implement optional verification at the end of a download if they exist

    companion object {
        suspend fun fetchFileProperties(httpClient: HttpClient, fileName: String): FileProperties {
            val response = httpClient.head(fileName)

            if (response.status != HttpStatusCode.OK) {
                throw FilePropertiesWrongStatusCodeException(response.status)
            }

            return FileProperties(
                contentLength = response.headers["content-length"]?.toInt() ?: 0,
                acceptRanges = response.headers["accept-ranges"]
            )
        }
    }
}

