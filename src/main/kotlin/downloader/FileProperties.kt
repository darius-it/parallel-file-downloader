package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.request.head

data class FileProperties (val contentLength: Int, val acceptRanges: String? = null) {
    companion object {
        suspend fun fetchFileProperties(httpClient: HttpClient, fileName: String): FileProperties? {
            val response = httpClient.head(fileName)

            if (response.status != io.ktor.http.HttpStatusCode.OK) {
                println("Something went wrong when getting the HEAD request! Status code: ${response.status}")

                // potentially do some better error handling here
                return null
            }

            return FileProperties(
                contentLength = response.headers["content-length"]?.toInt() ?: 0,
                acceptRanges = response.headers["accept-ranges"]
            )
        }
    }
}

