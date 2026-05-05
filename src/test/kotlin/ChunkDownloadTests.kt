import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import io.ktor.client.HttpClient

class ChunkDownloadTests {
    private val httpClient = HttpClient(WebServerMock.getMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData))
    private val downloader = TestFixtures.makeDownloader(httpClient)

    @Test
    fun testDownloadChunkWritesExpectedBytesToDisk() {
        val destination = Files.createTempFile("downloader-chunk", ".bin")
        try {
            runBlocking {
                downloader.downloadChunk("${TestFixtures.serverUrl}/${TestFixtures.fileName}", destination, 0 to 512)
            }

            val downloadedBytes = Files.readAllBytes(destination)
            assertEquals(512, downloadedBytes.size)
            assertArrayEquals(TestFixtures.testFileData.copyOfRange(0, 512), downloadedBytes)
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun testDownloadChunkWritesAtTheRequestedOffset() {
        val destination = Files.createTempFile("downloader-offset", ".bin")
        Files.write(destination, ByteArray(TestFixtures.testFileSize))

        try {
            runBlocking {
                downloader.downloadChunk("${TestFixtures.serverUrl}/${TestFixtures.fileName}", destination, 512 to 1024)
            }

            val downloadedBytes = Files.readAllBytes(destination)
            assertEquals(TestFixtures.testFileSize, downloadedBytes.size)
            assertTrue(downloadedBytes.copyOfRange(0, 512).all { it == 0.toByte() })
            assertArrayEquals(TestFixtures.testFileData.copyOfRange(512, 1024), downloadedBytes.copyOfRange(512, 1024))
        } finally {
            Files.deleteIfExists(destination)
        }
    }
}
