package me.dariusit.downloader

import io.ktor.client.HttpClient
import io.ktor.client.request.head

data class FileProperties (val contentLength: Int, val acceptRanges: String? = null) {
    // TODO: see if we can easily pull checksum from file server without downloading entire file
    // Idea: add checksum files like for Linux ISOs and implement optional verification at the end of a download if they exist

    companion object {
        suspend fun fetchFileProperties(httpClient: HttpClient, fileName: String): FileProperties? {
            val response = httpClient.head(fileName)

            // TODO: add custom exception

            if (response.status != io.ktor.http.HttpStatusCode.OK) {
                println("Something went wrong when getting the HEAD request! Status code: ${response.status}")

                // potentially do some better error handling here
                // TODO: add custom exception here, something like UnexpectedStatusCodeException
                return null
            }

            return FileProperties(
                contentLength = response.headers["content-length"]?.toInt() ?: 0,
                acceptRanges = response.headers["accept-ranges"]
            )
        }
    }
}

