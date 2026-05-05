import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import me.dariusit.downloader.ChunkSizeMismatchException
import me.dariusit.downloader.Downloader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.random.Random

class DownloaderTests {
    private val testFileSize = 1024
    private val testFileData = Random.nextBytes(testFileSize)
    private val serverUrl = "https://mockserver"
    private val fileName = "testfile"

    // Mock engine that simulates a file server supporting range requests
    private val defaultMockEngine = WebServerMock.getMockEngine(testFileSize, testFileData)

    private val trackedDownloadArtifacts = mutableListOf<Path>()

    private lateinit var httpClient: HttpClient
    private lateinit var downloader: Downloader
    private lateinit var logger: KLogger

    @BeforeEach
    fun setup() {
        logger = logger {}
        httpClient = HttpClient(defaultMockEngine)
        downloader = Downloader(httpClient = httpClient, logger = logger)
    }

    @AfterEach
    fun cleanup() {
        trackedDownloadArtifacts.forEach { Files.deleteIfExists(it) }
        trackedDownloadArtifacts.clear()
    }

    private fun trackDownloadArtifact(fileName: String): String {
        val path = Paths.get(fileName)
        Files.deleteIfExists(path)
        trackedDownloadArtifacts.add(path)
        return fileName
    }

    private fun newDownloadArtifactName(prefix: String = "download-test"): String {
        return trackDownloadArtifact("$prefix-${UUID.randomUUID()}.bin")
    }

    private fun readBytes(path: Path): ByteArray = Files.readAllBytes(path)

    private fun assertDownloadedFileEquals(expected: ByteArray, artifactName: String) {
        val artifactPath = Paths.get(artifactName)
        assertTrue(Files.exists(artifactPath), "Expected downloaded file $artifactName to exist")
        assertArrayEquals(expected, readBytes(artifactPath), "Downloaded file content does not match expected bytes")
    }

    @Nested
    inner class ChunkRangeTests {
        @Test
        fun testCalculateChunkRangesDistributesBytesEvenly() {
            assertEquals(listOf(0 to 3, 3 to 6, 6 to 8, 8 to 10), downloader.calculateChunkRanges(10, 4))
            assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), downloader.calculateChunkRanges(5, 3))
        }

        @Test
        fun testCalculateChunkRangesCapsChunkCountAtFileSize() {
            assertEquals(listOf(0 to 1), downloader.calculateChunkRanges(1, 4))
            assertEquals(listOf(0 to 1, 1 to 2), downloader.calculateChunkRanges(2, 4))
        }

        @Test
        fun testCalculateChunkRangesRejectsInvalidInput() {
            assertThrows(IllegalArgumentException::class.java) { downloader.calculateChunkRanges(0, 4) }
            assertThrows(IllegalArgumentException::class.java) { downloader.calculateChunkRanges(10, 0) }
        }
    }

    @Nested
    inner class DestinationPathTests {
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

    @Nested
    inner class FilePropertiesTests {
        @Test
        fun testGetFilePropertiesFetchesMetadata() {
            runBlocking {
                val properties = downloader.getFileProperties(fileName, serverUrl)

                assertEquals(testFileSize, properties.contentLength)
                assertEquals("bytes", properties.acceptRanges)
            }
        }

        @Test
        fun testGetFilePropertiesRejectsInvalidChunkCount() {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    downloader.getFileProperties(fileName, serverUrl, 0)
                }
            }
        }
    }

    @Nested
    inner class RetryTests {
        @Test
        fun testTryCatchWithRetryRetriesRetryableExceptions() {
            runBlocking {
                var attempts = 0

                downloader.tryCatchWithRetry(maxRetries = 2) {
                    attempts++
                    if (attempts < 3) {
                        throw ChunkSizeMismatchException(attempts, 2, 0 to 1)
                    }
                }

                assertEquals(3, attempts)
            }
        }

        @Test
        fun testTryCatchWithRetryDoesNotRetryNonRetryableExceptions() {
            runBlocking {
                var attempts = 0

                val thrown = assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        downloader.tryCatchWithRetry(maxRetries = 2) {
                            attempts++
                            throw IllegalStateException("boom")
                        }
                    }
                }

                assertEquals("boom", thrown.message)
                assertEquals(1, attempts)
            }
        }
    }

    @Nested
    inner class ChunkDownloadTests {
        @Test
        fun testDownloadChunkWritesExpectedBytesToDisk() {
            val destination = Files.createTempFile("downloader-chunk", ".bin")
            try {
                runBlocking {
                    downloader.downloadChunk("$serverUrl/$fileName", destination, 0 to 512)
                }

                val downloadedBytes = readBytes(destination)
                assertEquals(512, downloadedBytes.size)
                assertArrayEquals(testFileData.copyOfRange(0, 512), downloadedBytes)
            } finally {
                Files.deleteIfExists(destination)
            }
        }

        @Test
        fun testDownloadChunkWritesAtTheRequestedOffset() {
            val destination = Files.createTempFile("downloader-offset", ".bin")
            Files.write(destination, ByteArray(testFileSize))

            try {
                runBlocking {
                    downloader.downloadChunk("$serverUrl/$fileName", destination, 512 to 1024)
                }

                val downloadedBytes = readBytes(destination)
                assertEquals(testFileSize, downloadedBytes.size)
                assertTrue(downloadedBytes.copyOfRange(0, 512).all { it == 0.toByte() })
                assertArrayEquals(testFileData.copyOfRange(512, 1024), downloadedBytes.copyOfRange(512, 1024))
            } finally {
                Files.deleteIfExists(destination)
            }
        }
    }

    @Nested
    inner class ParallelDownloadTests {
        @Test
        fun testDownloadInParallelStreamsChunksToDisk() {
            val destination = Files.createTempFile("downloader-parallel", ".bin")
            Files.write(destination, ByteArray(testFileSize))

            try {
                runBlocking {
                    val fileProperties = downloader.getFileProperties(fileName, serverUrl)
                    val chunkRanges = downloader.calculateChunkRanges(fileProperties.contentLength, 4)

                    downloader.downloadInParallel(
                        "$serverUrl/$fileName",
                        destination,
                        chunkRanges,
                        fileProperties.contentLength
                    )
                }

                assertArrayEquals(testFileData, readBytes(destination))
            } finally {
                Files.deleteIfExists(destination)
            }
        }

        @Test
        fun testDownloadFileWritesExactContentForDifferentChunkCounts() {
            listOf(1, 2, 4, 7, 101).forEach { chunks ->
                val artifactName = newDownloadArtifactName("download-file-$chunks")

                runBlocking {
                    downloader.downloadFile(artifactName, serverUrl, chunks)
                }

                assertDownloadedFileEquals(testFileData, artifactName)
            }
        }

        @Test
        fun testDownloadFailsWhenOneChunkFails() {
            val failureMockEngine = WebServerMock.getFailureMockEngine()
            val failingDownloader = Downloader(httpClient = HttpClient(failureMockEngine), logger = logger)

            assertThrows(Exception::class.java) {
                runBlocking {
                    failingDownloader.downloadFile(newDownloadArtifactName("failure-download"), "https://faulty-server", 4)
                }
            }
        }

        @Test
        fun testDownloadHandlesNetworkDelays() {
            val slowMockEngine = WebServerMock.getDelayedChunkMockEngine(testFileSize, testFileData)
            val slowDownloader = Downloader(httpClient = HttpClient(slowMockEngine), logger = logger)

            val artifactName = newDownloadArtifactName("slow-download")

            runBlocking {
                slowDownloader.downloadFile(artifactName, "https://slow-server", 4)
            }

            assertDownloadedFileEquals(testFileData, artifactName)
        }

        @Test
        fun testChunkAssemblyWithDelays() {
            val slowMockEngine = WebServerMock.getDelayedChunkMockEngine(testFileSize, testFileData)
            val slowDownloader = Downloader(httpClient = HttpClient(slowMockEngine), logger = logger)

            val artifactName = newDownloadArtifactName("chunk-assembly-delayed")

            runBlocking {
                slowDownloader.downloadFile(artifactName, "https://slow-server", 4)
            }

            assertDownloadedFileEquals(testFileData, artifactName)
        }

        @Test
        fun testDownloadFileRejectsInvalidInputs() {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    downloader.downloadFile("", serverUrl, 2)
                }
            }

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    downloader.downloadFile("invalid-input-${UUID.randomUUID()}.bin", "", 2)
                }
            }
        }
    }
}