# Parallel File Downloader

[![Tests](https://github.com/darius-it/parallel-file-downloader/actions/workflows/run-tests.yml/badge.svg)](https://github.com/darius-it/parallel-file-downloader/actions/workflows/run-tests.yml)

A simple tool to download files from a web server in chunks (using the `Range` header) which are downloaded in parallel.
This implementation builds on a previous submission, but has been significantly rewritten, including many improvements
discussed in the last interview.

(The old README with some more details on the initial implementation can be found
in [the README_old.md](README_old.md).)

## How to use

Prerequisite is a web server which supports downloading in chunks using the `Range` header (so for example an Apache web
server pointed to serve some files to be downloaded).

The downloader logic is exposed through the `downloadFile()` method of a `Downloader` object.

In our example app, we use an `AppContainer` which uses Dependency Injection to pass the required dependencies (Ktor
HTTP Client and logger) to a Downloader object. You can obtain and object and use it as follows:

```kotlin
val container = AppContainer()
val downloader = container.getDownloaderInstance()

downloader.downloadFile(
    "someFileName.png", // file name to download
    "http://localhost:3210", // server URL, defaults to http://localhost:8080
    4, // # of chunks to download in parallel, defaults to 2
)
```

If you want to manually create a `Downloader` object, you can configure it as follows:

```kotlin
val customDownloader = Downloader(
    defaultServerUrl = "http://localhost:8080",
    parallelChunkAmount = 2,
    chunkFailureRetries = 3,
    httpClient = HTTPClient(/* ... */), // some Ktor HTTPClient implementation,
    logger = KLogger(/* ... */) // some implementation of KotlinLogger
)
```

## Improvements compared to previous implementation

TODO