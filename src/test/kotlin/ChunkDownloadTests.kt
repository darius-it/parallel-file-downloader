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
                downloader.downloadChunk("${TestFixtures.serverUrl}/${TestFixtures.fileName}", destination, 0L to 512L)
            }

            val downloadedBytes = Files.readAllBytes(destination)
            assertEquals(512L, downloadedBytes.size.toLong())
            assertArrayEquals(TestFixtures.testFileData.copyOfRange(0, 512), downloadedBytes)
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun testDownloadChunkWritesAtTheRequestedOffset() {
        val destination = Files.createTempFile("downloader-offset", ".bin")
        Files.write(destination, ByteArray(TestFixtures.testFileSize.toInt()))

        try {
            runBlocking {
                downloader.downloadChunk("${TestFixtures.serverUrl}/${TestFixtures.fileName}", destination, 512L to 1024L)
            }

            val downloadedBytes = Files.readAllBytes(destination)
            assertEquals(TestFixtures.testFileSize, downloadedBytes.size.toLong())
            assertTrue(downloadedBytes.copyOfRange(0, 512).all { it == 0.toByte() })
            assertArrayEquals(TestFixtures.testFileData.copyOfRange(512, 1024), downloadedBytes.copyOfRange(512, 1024))
        } finally {
            Files.deleteIfExists(destination)
        }
    }
}
