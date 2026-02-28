import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngine.Companion.invoke
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlin.text.removePrefix

object WebServerMock {
    fun getMockEngine(testFileSize: Int, testFileData: ByteArray): MockEngine {
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
                    val start = range[0].toInt()
                    // If end is missing or empty, it means "to end", but let's assume it's always present as per our client
                    val end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toInt() else testFileSize - 1

                    if (start >= testFileSize || end >= testFileSize || start > end) {
                        respond("Range Not Satisfiable", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                    } else {
                        val chunk = testFileData.sliceArray(start..end)
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
}