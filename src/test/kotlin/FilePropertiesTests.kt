import io.ktor.client.HttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking

class FilePropertiesTests {
    private val httpClient = HttpClient(WebServerMock.getMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData))
    private val downloader = TestFixtures.makeDownloader(httpClient)

    @Test
    fun testGetFilePropertiesFetchesMetadata() {
        runBlocking {
            val properties = downloader.getFileProperties(TestFixtures.fileName, TestFixtures.serverUrl)

            assertEquals(TestFixtures.testFileSize, properties.contentLength)
            assertEquals("bytes", properties.acceptRanges)
        }
    }

    @Test
    fun testGetFilePropertiesHandlesLargeContentLengthHeaders() {
        val largeSize = 2_851_612_672L
        val largeHttpClient = HttpClient(WebServerMock.getMockEngine(largeSize, ByteArray(0)))
        val largeDownloader = TestFixtures.makeDownloader(largeHttpClient)

        runBlocking {
            val properties = largeDownloader.getFileProperties(TestFixtures.fileName, TestFixtures.serverUrl)

            assertEquals(largeSize, properties.contentLength)
        }
    }

    @Test
    fun testGetFilePropertiesRejectsInvalidChunkCount() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                downloader.getFileProperties(TestFixtures.fileName, TestFixtures.serverUrl, 0)
            }
        }
    }
}
