import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking
import me.dariusit.downloader.ChunkSizeMismatchException
import io.ktor.client.HttpClient

class RetryTests {
    private val httpClient = HttpClient(WebServerMock.getMockEngine(TestFixtures.testFileSize, TestFixtures.testFileData))
    private val downloader = TestFixtures.makeDownloader(httpClient)

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
