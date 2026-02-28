# Parallel File Downloader

A simple tool to download files from a web server in chunks (using the `Range` header) which are downloaded in parallel. Project made for the made for the JB Internship application Summer/Fall 2026

## How to use
The downloader logic is exposed through the `downloadFile()` method, which can be used as follows:

```kotlin
downloadFile(
    "someFileName.png",
    "http://localhost:3210", //defaults to http://localhost:8080
    4, // # of chunks to download in parallel, defaults to 2
    true // whether to save file to disk, defaults to true
)
```

All the logic is contained inside the `downloader` package, which could theoretically be extracted into a library. 

Moreover, because the implementation uses Ktor for a HTTP Client (with the CIO engine), this could also be used in Kotlin Multiplatform projects (except saving to disk, which would need to use a library like FileKit to work properly).

## How I approached the problem
TODO