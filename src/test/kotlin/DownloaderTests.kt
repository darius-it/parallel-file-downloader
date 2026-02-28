import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.dariusit.downloader.Downloader
import me.dariusit.downloader.FileChunk
import me.dariusit.downloader.FileProperties
import me.dariusit.downloader.FileProperties.Companion.fetchFileProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DownloaderTests {
    private val testFileSize = 1024
    private val testFileData = Random.nextBytes(testFileSize)

    // Mock Engine that simulates a file server supporting range requests
    private val mockEngine = MockEngine { request ->
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

    private lateinit var httpClient: HttpClient

    @BeforeEach
    fun setup() {
        httpClient = HttpClient(mockEngine)
        Downloader.client = httpClient
    }

    @Test
    fun testCalculateChunkRanges() {
        val totalSizes = listOf(1, 10, 100, 500, 999, 1000, 1001, 5000, 10000)
        val chunkSize = 500

        for (totalSize in totalSizes) {
            val chunkRanges = Downloader.calculateChunkRanges(totalSize, chunkSize)

            // check that the chunk ranges cover the entire file without overlap
            var coveredBytes = 0
            for (range in chunkRanges) {
                assertEquals(range.first, coveredBytes) {
                    "Chunk range does not start where the last one ended! Expected ${coveredBytes}, but got ${range.first}" }
                assertTrue(range.second <= totalSize) {
                    "Chunk range end exceeds total file size! Expected at most ${totalSize}, but got ${range.second}" }
                coveredBytes += (range.second - range.first)
            }

            assertEquals(totalSize, coveredBytes) { "Chunk ranges do not cover the entire file! Expected to cover ${totalSize} bytes, but covered ${coveredBytes} bytes" }
        }
    }

    @Test
    fun testFileProperties() {
        val fileProperties: FileProperties?

        runBlocking {
            fileProperties = fetchFileProperties(httpClient, "https://mockserver/testfile")
        }

        // check if fetching worked and we got all the wanted properties
        assertTrue(fileProperties != null) { "Failed to fetch file properties!" }
        assertTrue(fileProperties?.acceptRanges != null) { "Server does not support range requests!" }
        assertEquals("bytes", fileProperties?.acceptRanges) {
            "Server does not support byte range requests! Got ${fileProperties?.acceptRanges} instead" }

        // check if content length is correct
        assertTrue(
            fileProperties?.contentLength!! > 0
                    && fileProperties.contentLength == testFileSize
        ) { "Content length is incorrect! Expected $testFileSize, but got ${fileProperties.contentLength}" }
    }

    @Test
    fun testFileChunkDownload() {
        val fileChunk: FileChunk?

        runBlocking { // grab a chunk of the test file and check if it has the correct size
            fileChunk = FileChunk.fetchChunk(httpClient, "https://mockserver/testfile", 0, 512)
        }

        // check if fetching worked, and we got the wanted chunk (with the right size)
        assertTrue(fileChunk != null) { "Failed to fetch file chunk!" }

        val expectedChunkRange = (fileChunk?.end ?: 0) - (fileChunk?.start ?: 0)
        assertEquals (fileChunk?.rawBytes?.size, expectedChunkRange){ "Chunk boundaries do not correspond to fetched chunk size!" }

        // probably redundant with previous check but it doesn't hurt to check both ways
        assertEquals(512, fileChunk?.rawBytes?.size) {
            "Fetched chunk has incorrect size! Expected 512 bytes, but got ${fileChunk?.rawBytes?.size} bytes" }
    }

    // tests for downloading file in 2,3,6 chunks, check if combined file == original (checksum and bytes)
    fun testDownloadInParallelVariableChunks(chunks: Int) {
        val serverUrl = "https://mockserver"
        val fileName = "testfile"

        runBlocking {
            val fileProperties = fetchFileProperties(httpClient, "$serverUrl/$fileName")
            val contentLength = fileProperties?.contentLength ?: 0

            val parallelDownloadedData = Downloader.downloadFile(fileName, serverUrl, chunks, false)

            val directDownload = httpClient.get("$serverUrl/$fileName")
            val completeFileData = directDownload.readRawBytes()

            // check if downloaded data has the correct size
            assertEquals(contentLength, parallelDownloadedData.size) {
                "Downloaded data size does not match expected content length! Expected $contentLength bytes, but got ${parallelDownloadedData.size} bytes" }

            // check if downloaded data matches the original file data
            assertTrue(parallelDownloadedData.contentEquals(completeFileData)) {
                "Downloaded data does not match original file data!"
            }

            // Also check agains the source of truth
            assertTrue(parallelDownloadedData.contentEquals(testFileData)) {
                "Downloaded data does not match the test file data source!"
            }
        }
    }

    @Test
    fun testDownloadInParallel2Chunks() {
        testDownloadInParallelVariableChunks(2)
    }

    @Test
    fun testDownloadInParallelDifferentChunkSizes() {
        val chunkSizes = listOf(1, 5, 7, 10, 99, 101)
        chunkSizes.forEach {
            println("Testing download in parallel with $it chunks...")
            testDownloadInParallelVariableChunks(it)
        }
    }

    /*
        Test that we throw some exceptions correctly
     */
    @Test
    fun testDownloadFileInvalidChunkCount() {
        assertThrows (IllegalArgumentException::class.java) {
            testDownloadInParallelVariableChunks(-1)
        }

        assertThrows (IllegalArgumentException::class.java) {
            testDownloadInParallelVariableChunks(0)
        }
    }

    @Test
    fun testChunkRangeInvalid() {
        assertThrows (IllegalArgumentException::class.java) {
            Downloader.calculateChunkRanges(0, 999)
        }

        assertThrows (IllegalArgumentException::class.java) {
            Downloader.calculateChunkRanges(1000, 0)
        }
    }

    @Test
    fun testEmptyFileOrServerName() {
        assertThrows (IllegalArgumentException::class.java) {
            runBlocking {
                Downloader.downloadFile("", "https://mockserver", 2, false)
            }
        }

        assertThrows (IllegalArgumentException::class.java) {
            runBlocking {
                Downloader.downloadFile("testfile", "", 2, false)
            }
        }
    }

    @Test
    fun testChunkDownloadFullFile() {
        assertThrows (Exception::class.java) {
            runBlocking {
                FileChunk.fetchChunk(httpClient, "https://mockserver/testfile", 0, testFileSize + 1)
            }
        }
    }
}