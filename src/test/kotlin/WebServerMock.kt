import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

object WebServerMock {
    fun getMockEngine(testFileSize: Long, testFileData: ByteArray): MockEngine {
        return MockEngine { request ->
            val path = request.url.encodedPath
            if (path == "/") return@MockEngine respond("Not Found", status = HttpStatusCode.NotFound)

            if (request.method == HttpMethod.Head) {
                respond(
                    content = ByteArray(0),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(testFileSize.toString()),
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentType to listOf("application/octet-stream")
                    )
                )
            } else if (request.method == HttpMethod.Get) {
                val rangeHeader = request.headers[HttpHeaders.Range]
                if (rangeHeader != null) {
                    // Parse Range: bytes=start-end
                    val range = rangeHeader.removePrefix("bytes=").split("-")
                    val start = range[0].toLong()
                    // If end is missing or empty, it means "to end", but let's assume it's always present as per our client
                    val end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else testFileSize - 1

                    if (start >= testFileSize || end >= testFileSize || start > end) {
                        respond("Range Not Satisfiable", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                    } else {
                        val chunk = testFileData.sliceArray(start.toInt()..end.toInt())
                        respond(
                            content = chunk,
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentLength to listOf(chunk.size.toString()),
                                HttpHeaders.ContentRange to listOf("bytes $start-$end/$testFileSize"),
                                HttpHeaders.ContentType to listOf("application/octet-stream")
                            )
                        )
                    }
                } else {
                    // Full file download
                    respond(
                        content = testFileData,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(testFileSize.toString()),
                            HttpHeaders.AcceptRanges to listOf("bytes"),
                            HttpHeaders.ContentType to listOf("application/octet-stream")
                        )
                    )
                }
            } else {
                respond("Method Not Allowed", status = HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    fun getFailureMockEngine(): MockEngine {
        return MockEngine { request ->
            val rangeHeader = request.headers[HttpHeaders.Range]
            // Simulate a failure only for a specific byte range (e.g., the second chunk)
            if (rangeHeader != null && rangeHeader.contains("bytes=512-")) {
                respond("Server Error", status = HttpStatusCode.InternalServerError)
            } else {
                // Return normal data for others
                respond(ByteArray(100), status = HttpStatusCode.PartialContent)
            }
        }
    }

    fun getDelayedChunkMockEngine(testFileSize: Long, testFileData: ByteArray): MockEngine {
        return MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                return@MockEngine respond(
                    content = ByteArray(0),
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(testFileSize.toString()),
                        HttpHeaders.AcceptRanges to listOf("bytes")
                    )
                )
            }

            val rangeHeader = request.headers[HttpHeaders.Range]

            if (rangeHeader != null && rangeHeader.contains("bytes=0-")) {
                delay(500)
            }

            if (rangeHeader != null) {
                val range = rangeHeader.removePrefix("bytes=").split("-")
                val start = range[0].toLong()
                val end = range[1].toLong()
                val chunk = testFileData.sliceArray(start.toInt()..end.toInt())

                respond(
                    content = chunk,
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(chunk.size.toString()),
                        HttpHeaders.ContentRange to listOf("bytes $start-${end}/$testFileSize")
                    )
                )
            } else {
                respond(testFileData, HttpStatusCode.OK)
            }
        }
    }

    /**
     * Create a mock engine for very large files where the content is generated on-the-fly per Range request.
     * This avoids allocating the entire file in memory; the engine returns deterministic bytes for each
     * requested range (byte value = position % 256).
     */
    fun getLargeMockEngine(totalSize: Long): MockEngine {
        return MockEngine { request ->
            // Handle HEAD requests
            if (request.method == HttpMethod.Head) {
                return@MockEngine respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(totalSize.toString()),
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentType to listOf("application/octet-stream")
                    )
                )
            }

            // Parse Range
            val rangeHeader = request.headers[HttpHeaders.Range]
            val (start, end) = if (rangeHeader != null) {
                val range = rangeHeader.removePrefix("bytes=").split("-")
                val s = range[0].toLong()
                val e = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else totalSize - 1
                s to e
            } else {
                0L to totalSize - 1
            }

            if (start >= totalSize || end >= totalSize || start > end) {
                return@MockEngine respond("Range Not Satisfiable", status = HttpStatusCode.RequestedRangeNotSatisfiable)
            }

            val rangeLength = end - start + 1

            // 1. Create a channel manually
            val channel = ByteChannel(autoFlush = true)

            // 2. Launch a background task to "feed" the channel
            // We use GlobalScope here because this is a Mock;
            // the channel will close when the download finishes.
            GlobalScope.launch {
                try {
                    val bufferSize = 8192
                    val buffer = ByteArray(bufferSize)
                    var currentPos = start

                    while (currentPos <= end) {
                        val remainingInRange = end - currentPos + 1
                        val bytesToFill = min(bufferSize.toLong(), remainingInRange).toInt()

                        for (i in 0 until bytesToFill) {
                            buffer[i] = ((currentPos + i) % 256).toByte()
                        }

                        channel.writeFully(buffer, 0, bytesToFill)
                        currentPos += bytesToFill
                    }
                } finally {
                    // IMPORTANT: Always close the channel or the client will hang forever
                    channel.close()
                }
            }

            respond(
                content = channel, // The client reads from the channel we are filling
                status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf(rangeLength.toString()),
                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$totalSize"),
                    HttpHeaders.ContentType to listOf("application/octet-stream")
                )
            )
        }
    }
}
