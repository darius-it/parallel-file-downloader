import io.ktor.client.HttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
// single import kept above

class ParallelDownloadTests {
    private val httpClient = HttpClient(WebServerMock.getMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData))
    private val downloader = TestFixtures.makeDownloader(httpClient)

    @Test
    fun testDownloadInParallelStreamsChunksToDisk() {
        val destination = Files.createTempFile("downloader-parallel", ".bin")
        Files.write(destination, ByteArray(TestFixtures.testFileSize))

        try {
            runBlocking {
                val fileProperties = downloader.getFileProperties(TestFixtures.fileName, TestFixtures.serverUrl)
                val chunkRanges = downloader.calculateChunkRanges(fileProperties.contentLength, 4)

                downloader.downloadInParallel(
                    "${TestFixtures.serverUrl}/${TestFixtures.fileName}",
                    destination,
                    chunkRanges,
                    fileProperties.contentLength
                )
            }

            assertArrayEquals(TestFixtures.testFileData, Files.readAllBytes(destination))
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun testDownloadFileWritesExactContentForDifferentChunkCounts() {
        listOf(1, 2, 4, 7, 101).forEach { chunks ->
            val artifactName = "download-file-$chunks-${java.util.UUID.randomUUID()}.bin"

            runBlocking {
                downloader.downloadFile(artifactName, TestFixtures.serverUrl, chunks)
            }

            val artifactPath = Paths.get(artifactName)
            try {
                assertTrue(Files.exists(artifactPath))
                assertArrayEquals(TestFixtures.testFileData, Files.readAllBytes(artifactPath))
            } finally {
                Files.deleteIfExists(artifactPath)
            }
        }
    }

    @Test
    fun testDownloadFailsWhenOneChunkFails() {
        val failureMockEngine = WebServerMock.getFailureMockEngine()
        val failingDownloader = TestFixtures.makeDownloader(HttpClient(failureMockEngine))

        assertThrows(Exception::class.java) {
            runBlocking {
                failingDownloader.downloadFile("failure-${java.util.UUID.randomUUID()}.bin", "https://faulty-server", 4)
            }
        }
    }

    @Test
    fun testDownloadHandlesNetworkDelays() {
        val slowMockEngine = WebServerMock.getDelayedChunkMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData)
        val slowDownloader = TestFixtures.makeDownloader(HttpClient(slowMockEngine))

        val artifactName = "slow-download-${java.util.UUID.randomUUID()}.bin"

        runBlocking {
            slowDownloader.downloadFile(artifactName, "https://slow-server", 4)
        }

        val artifactPath = Paths.get(artifactName)
        try {
            assertArrayEquals(TestFixtures.testFileData, Files.readAllBytes(artifactPath))
        } finally {
            Files.deleteIfExists(artifactPath)
        }
    }
}
