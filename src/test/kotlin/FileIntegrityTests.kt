import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.security.MessageDigest

class FileIntegrityTests {
	@Test
	fun testVerifyFileChecksumMatchesRemote() {
		val tempFile = Files.createTempFile("checksum-test", ".bin")

		try {
			val content = "test-content-for-checksum".toByteArray()
			Files.write(tempFile, content)

			val md = MessageDigest.getInstance("SHA-256")
			val localDigest = md.digest(content)
			val localHex = localDigest.joinToString("") { "%02x".format(it) }

			// Mock engine returns the checksum text for the <filename>.sha256 path
			val mockEngine = MockEngine { request ->
				val expectedPath = "/${tempFile.fileName}.sha256"
				if (request.url.encodedPath == expectedPath) {
					respond(
						content = localHex,
						status = HttpStatusCode.OK,
						headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()))
					)
				} else {
					respond("Not Found", HttpStatusCode.NotFound)
				}
			}

			val client = HttpClient(mockEngine)
			val downloader = TestFixtures.makeDownloader(client)

			// verifyFileChecksum currently throws on mismatch and returns Unit on success
			// assert that the call does not throw
			assertDoesNotThrow {
				runBlocking {
					downloader.verifyFileChecksum(tempFile, "https://mock")
				}
			}
		} finally {
			Files.deleteIfExists(tempFile)
		}
	}
}