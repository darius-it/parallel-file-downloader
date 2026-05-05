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
