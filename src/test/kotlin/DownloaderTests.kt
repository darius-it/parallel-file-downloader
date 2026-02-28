import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import me.dariusit.downloader.Downloader
import me.dariusit.downloader.FileChunk
import me.dariusit.downloader.FileProperties
import me.dariusit.downloader.FileProperties.Companion.fetchFileProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloaderTests {
    /*
        - check if combined file == original file
        - test edge cases like downloading empty file
        - check file checksum, check individual chunks for correctness (can we get checksum from server?)
     */

    val httpClient = HttpClient(CIO)

    @Test
    fun testCalculateChunkRanges() {
        val totalSizes = listOf(0, 1, 10, 100, 500, 999, 1000, 1001, 5000, 10000)
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
            fileProperties = fetchFileProperties(httpClient, "https://httpbin.org/range/1024") // test endpoint, 1024 bytes of random data
        }

        // check if fetching worked and we got all the wanted properties
        assertTrue(fileProperties != null) { "Failed to fetch file properties!" }
        assertTrue(fileProperties?.acceptRanges != null) { "Server does not support range requests!" }
        assertEquals("bytes", fileProperties?.acceptRanges) {
            "Server does not support byte range requests! Got ${fileProperties?.acceptRanges} instead" }

        // check if content length is correct
        assertTrue(
            fileProperties?.contentLength!! > 0
                    && fileProperties.contentLength == 1024
        ) { "Content length is incorrect! Expected 1024, but got ${fileProperties.contentLength}" }
    }

    @Test
    fun testFileChunkDownload() {
        val fileChunk: FileChunk?

        runBlocking { // grab a chunk of the test file and check if it has the correct size
            fileChunk = FileChunk.fetchChunk(httpClient, "https://httpbin.org/range/1024", 0, 512)
        }

        // check if fetching worked, and we got the wanted chunk (with the right size)
        assertTrue(fileChunk != null) { "Failed to fetch file chunk!" }

        val expectedChunkRange = (fileChunk?.start ?: 0) - (fileChunk?.end ?: 0)
        assertEquals (fileChunk?.rawBytes?.size, expectedChunkRange){ "Chunk boundaries do not correspond to fetched chunk size!" }

        // probably redundant with previous check but it doesn't hurt to check both ways
        assertEquals(512, fileChunk?.rawBytes?.size) {
            "Fetched chunk has incorrect size! Expected 512 bytes, but got ${fileChunk?.rawBytes?.size} bytes" }
    }

    // TODO: add a test where we download a chunk in full, expect an exception because status won't be 206
}