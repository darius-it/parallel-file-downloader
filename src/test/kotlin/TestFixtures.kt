import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import kotlin.random.Random
import me.dariusit.downloader.Downloader

object TestFixtures {
    const val testFileSize = 1024L
    val testFileData: ByteArray = Random.nextBytes(testFileSize.toInt())
    val serverUrl = "https://mockserver"
    val fileName = "testfile"

    fun makeDownloader(httpClient: HttpClient) = Downloader(httpClient = httpClient, logger = logger {})
}
