import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
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
    private val mockEngine = WebServerMock.getMockEngine(1024, testFileData)

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

        runBlocking {
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

            assertEquals(contentLength, parallelDownloadedData.size) {
                "Downloaded data size does not match expected content length! Expected $contentLength bytes, but got ${parallelDownloadedData.size} bytes" }

            assertTrue(parallelDownloadedData.contentEquals(completeFileData)) {
                "Downloaded data does not match original file data!"
            }

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

    @Test
    fun testDownloadFailsWhenOneChunkFails() {
        val failureMockEngine = WebServerMock.getFailureMockEngine()

        val errorClient = HttpClient(failureMockEngine)
        Downloader.client = errorClient

        assertThrows(Exception::class.java) {
            runBlocking {
                Downloader.downloadFile("testfile", "https://faulty-server", 4, false)
            }
        }
    }

    @Test
    fun testChunkAssemblyWithDelays() {
        val slowMockEngine = WebServerMock.getDelayedChunkMockEngine(testFileSize, testFileData)

        val slowClient = HttpClient(slowMockEngine)
        Downloader.client = slowClient

        runBlocking {
            val downloadedData = Downloader.downloadFile("testfile", "https://slow-server", 4, false)
            assertTrue(downloadedData.contentEquals(testFileData)) {
                "Parallel chunks were not assembled in the correct order."
            }
        }
    }
}