import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking
import io.ktor.client.HttpClient
import java.nio.file.Files

/**
 * Integration test for very large files (>= 2GB). Disabled by default because it requires
 * significant disk space and runtime. Run manually when you want to validate streaming behavior.
 */
@Disabled("Integration test for large files; run manually when you have sufficient disk and time")
class LargeFileStreamingTests {
    @Test
    fun testLargeFileStreamingDoesNotAllocateHugeByteArray() {
        // Use Int.MAX_VALUE (~2.147GB) to represent a file just above 2GB
        val largeSize = Int.MAX_VALUE
        val mockEngine = WebServerMock.getLargeMockEngine(largeSize)
        val httpClient = HttpClient(mockEngine)
        val downloader = TestFixtures.makeDownloader(httpClient)

        val artifactName = "large-file-${java.util.UUID.randomUUID()}.bin"

        // This test is disabled by default; when enabled it will actually perform the download and write
        // a very large file to disk. Ensure you have enough free disk space before running.
        runBlocking {
            downloader.downloadFile(artifactName, TestFixtures.serverUrl, 8192)
        }

        val path = java.nio.file.Paths.get(artifactName)
        try {
            assertTrue(Files.exists(path))
            assertEquals(largeSize.toLong(), Files.size(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
