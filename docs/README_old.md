# Parallel File Downloader

[![Tests](https://github.com/darius-it/parallel-file-downloader/actions/workflows/run-tests.yml/badge.svg)](https://github.com/darius-it/parallel-file-downloader/actions/workflows/run-tests.yml)

A simple tool to download files from a web server in chunks (using the `Range` header) which are downloaded in parallel.

## How to use

Prerequisite is a web server which supports downloading in chunks using the `Range` header (so for example an Apache web
server pointed to serve some files to be downloaded).

The downloader logic is exposed through the `downloadFile()` method, which can be used as follows:

```kotlin
downloadFile(
    "someFileName.png", // file name to download
    "http://localhost:3210", // server URL, defaults to http://localhost:8080
    4, // # of chunks to download in parallel, defaults to 2
    true // whether to save file to disk, defaults to true
)
```

All the logic is contained inside the `downloader` package, which could theoretically be extracted into a reusable
library.

## Running the tests

The tests are written using JUnit and can be run from IntelliJ or directly through Gradle. To run the tests through
Gradle, use the following command in the terminal:

```bash
./gradlew test
```

## How I approached the problem

1. First, I tested out the web server launched through the Docker command to familiarize myself with file downloading
   and how the Range header works. For this I used Bruno (similar to Postman) to test different HTTP requests.

2. Then, I quickly wrote down the abstract flow of how the downloader should work. I've illustrated this with a simple
   schematic: <br> ![download process overview](download_process_overview.svg)

3. With this flow in mind, I jumped into the initial prototype. Since I've previously worked with Ktor in a Compose
   Multiplatform project, I chose that as a technology that works well as a HTTP client and is pretty powerful and
   configurable. <br> This prototype focused on purely getting the main idea running quickly, so it was all inside one
   file with nearly no error handling. The basic concept did seem to work, so I was able to continue with this
   implementation.

4. To prepare for testing, I extracted the steps in the two main stages (HEAD for metadata, GET for chunks) and created
   according data classes to enclose
   all data and required methods. Finally, I combined all the other bits and pieces together into a Downloader object,
   which provides the main `downloadFile` method which handles the entire download logic.

5. After the basic implementation was done, my goal for testing was covering the main download stages (each method
   available in my downloader package). To test my logic in isolation, because many parts depend on HTTP
   requests, I used a Ktor mock engine which simulates our expected responses from the web server without relying on
   actual HTTP requests. With some JUnit tests, I covered the primary cases where downloads should work but also tested
   some cases where errors should be thrown (e.g. empty parameters, trying to download 0 chunks).

6. Lastly, I added some more complex tests to test cases where fundamental assumptions about our web server don't apply,
   for example one chunk returning an internal server error. (In that case our download should stop immediately and
   throw an error).

## Technical observations

Key points of this implementation:

- Idiomatic Kotlin (coroutines, data classes, etc.) and Ktor were very suited to implement a simple solution
  that leverages powerful language features.
- Particularly, Coroutines were useful to defer the individual chunk downloads, await their results and aggregate them
  in the end.
- Ktor is also fitting because it abstracts away the HTTP Client logic, making the downloader compatible for Kotlin
  Multiplatform too.
- Another thing I enjoyed about Ktor was the ability to test HTTP Client logic by creating mock engines. Like this,
  I was able to simulate different web server behavior reliably and in a lightweight way.
- During development, I identified a performance improvement by replacing the `reduce` method with a loop-based
  approach. This avoids creating temporary arrays on each iteration and improves memory efficiency through simple
  appending (O(n) complexity).

What could be improved & ideas to add:

- Some methods could be split up into smaller pieces to make more focused/isolated unit tests.
- Another feature which could be implemented for a real-world scenario would be enabling retries on the Ktor
  client. This would make fetching more robust and account for temporary web server failures.
- Some other interesting ideas could be turning the downloader into a CLI application or actual reusable library.
- The only change that would be needed for Kotlin Multiplatform compatibility on non-JVM targets is a platform-specific
  implementation for
  downloading files to disk (for example using a library like FileKit).
