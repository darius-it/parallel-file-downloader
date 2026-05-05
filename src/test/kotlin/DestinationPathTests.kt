import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import io.ktor.client.HttpClient

class DestinationPathTests {
    private lateinit var downloader: me.dariusit.downloader.Downloader

    @BeforeEach
    fun setup() {
        val httpClient = HttpClient(WebServerMock.getMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData))
        downloader = TestFixtures.makeDownloader(httpClient)
    }

    @Test
    fun testGetDestinationFilePathCreatesMissingFile() {
        val tempDir = Files.createTempDirectory("downloader-path-test")
        try {
            val destination = tempDir.resolve("new-file.bin")
            assertFalse(Files.exists(destination))

            val resolved = downloader.getDestinationFilePath(destination.toString())

            assertEquals(destination, resolved)
            assertTrue(Files.exists(destination))
        } finally {
            Files.deleteIfExists(tempDir.resolve("new-file.bin"))
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun testGetDestinationFilePathReturnsExistingFile() {
        val existing = Files.createTempFile("downloader-existing", ".bin")
        try {
            val resolved = downloader.getDestinationFilePath(existing.toString())

            assertEquals(existing, resolved)
            assertTrue(Files.exists(existing))
        } finally {
            Files.deleteIfExists(existing)
        }
    }
}
