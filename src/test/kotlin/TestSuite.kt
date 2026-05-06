import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses(
    ChunkDownloadTests::class,
    ChunkRangeTests::class,
    DestinationPathTests::class,
    FilePropertiesTests::class,
    LargeFileStreamingTests::class, // disabled by default, only enable if enough space and time available
    ParallelDownloadTests::class,
    RetryTests::class
)
class DownloaderTestSuite