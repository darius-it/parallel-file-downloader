import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import kotlin.random.Random
import me.dariusit.downloader.Downloader

object TestFixtures {
    val testFileSize = 1024
    val testFileData: ByteArray = Random.nextBytes(testFileSize)
    val serverUrl = "https://mockserver"
    val fileName = "testfile"

    fun makeDownloader(httpClient: HttpClient) = Downloader(httpClient = httpClient, logger = logger {})
}
