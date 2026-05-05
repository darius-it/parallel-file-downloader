package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

class FilePropertiesWrongStatusCodeException(statusCode: HttpStatusCode) :
    Exception("Failed to get file information via HEAD request (received unexpected HTTP status code $statusCode)!")

data class FileProperties (val contentLength: Long, val acceptRanges: String? = null) {
    companion object {
        /**
         * Fetch file metadata via HTTP HEAD request.
         *
         * @param httpClient the Ktor HTTP client to use for the request
         * @param fileName the URL of the file to query
         * @return FileProperties containing content length and accept-ranges header
         * @throws FilePropertiesWrongStatusCodeException if HEAD request returns status code other than OK (200)
         */
        suspend fun fetchFileProperties(httpClient: HttpClient, fileName: String): FileProperties {
            val response = httpClient.head(fileName)

            if (response.status != HttpStatusCode.OK) {
                throw FilePropertiesWrongStatusCodeException(response.status)
            }

            return FileProperties(
                contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L,
                acceptRanges = response.headers[HttpHeaders.AcceptRanges]
            )
        }
    }
}

