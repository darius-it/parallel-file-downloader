import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import io.ktor.client.HttpClient

class ChunkRangeTests {
    private lateinit var downloader: me.dariusit.downloader.Downloader

    @BeforeEach
    fun setup() {
        val httpClient = HttpClient(WebServerMock.getMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData))
        downloader = TestFixtures.makeDownloader(httpClient)
    }

    @Test
    fun testCalculateChunkRangesDistributesBytesEvenly() {
        assertEquals(listOf(0L to 3L, 3L to 6L, 6L to 8L, 8L to 10L), downloader.calculateChunkRanges(10L, 4))
        assertEquals(listOf(0L to 2L, 2L to 4L, 4L to 5L), downloader.calculateChunkRanges(5L, 3))
    }

    @Test
    fun testCalculateChunkRangesCapsChunkCountAtFileSize() {
        assertEquals(listOf(0L to 1L), downloader.calculateChunkRanges(1L, 4))
        assertEquals(listOf(0L to 1L, 1L to 2L), downloader.calculateChunkRanges(2L, 4))
    }

    @Test
    fun testCalculateChunkRangesRejectsInvalidInput() {
        assertThrows(IllegalArgumentException::class.java) { downloader.calculateChunkRanges(0L, 4) }
        assertThrows(IllegalArgumentException::class.java) { downloader.calculateChunkRanges(10L, 0) }
    }
}
