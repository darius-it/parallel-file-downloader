# Parallel File Downloader

A simple tool to download files from a web server in chunks (using the `Range` header) which are downloaded in parallel.

## How to use

Prerequisite is a web server which supports downloading in chunks using the `Range` header (so for example an Apache web
server pointed to server some files to be downloaded).

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
   schematic: <br> ![download process overview](docs/download_process_overview.svg)

3. With this flow in mind, I jumped into the initial prototype. Since I've previously worked with Ktor in a Compose
   Multiplatform project, I chose that as a technology that works well as a HTTP client and is pretty powerful and
   configurable. <br> This prototype focused on purely getting the main idea running quickly, so it was all inside one
   file with nearly no error handling. The basic concept did seem to work, so I was able to continue with this
   implementation.

4. To prepare for testing, I extracted the steps in the two main stages (HEAD for metadata, GET for chunks) and created
   according data classes to enclose
   all data and required methods. Finally, I combined all the other bits and pieces together into a Downloader object,
   which provides the main `downloadFile` method which handles the entire download logic.

5. After the general implementation was done, my goal for testing was covering the main stages of the download process (
   so each method available in my downloader package). Because Ktor and Kotlin Coroutines do a lot of heavy lifting, at
   some points it was difficult to create "actual" unit tests, so you can argue they are somewhere between a unit and
   integration test (they use a Ktor mock engine to simulate the behaviour of our web server). <br> Nevertheless, there
   are tests like `testCalculateChunkRanges` which test logic in isolation and don't depend on our Ktor HTTP client.

6. Lastly, I added some more complex tests to test cases where fundamental assumptions about our web server don't apply,
   for example the Range header not working.

Overall, the implementation was not the difficult part, rather knowing what exactly to test and how far to go with it.
While there is lots to handle and I couldn't possibly cover everything, the core logic should be reasonably robust now.

In the end, my focus was on progressively improving my prototype and making something that works, because this seemingly
trivial task of downloading a file offers room for endless optimization.

## Technical observations

What works well with my implementation:

- By using idiomatic Kotlin (coroutines, data classes, etc.) and Ktor it was very easy to implement a simple solution
  that leverages powerful language features.
- Particularly, Coroutines made it very simple to defer results, wait for them to finish and put them back together in
  the order they were called.
- Ktor also helps a lot because it abstracts away the HTTP Client logic, making the downloader compatible for Kotlin
  Multiplatform too. The only change that would be needed for non-JVM targets is a different implementation for
  downloading files to disk.
- Another thing I enjoyed about Ktor was also the ability to test HTTP Client logic by creating mock engines. Like this,
  I was able to simulate different web server behavior reliably and in a lightweight way.
- Lastly, I noticed that I was sacrificing memory usage and potentially performance by using the `reduce` methode (
  because functional methods work immutably, we create a new temp array every time we add a chunk onto the accumulator).
  To solve this, I opted for a more naive approach with loops.

What could be improved & ideas to add:

- Some methods could be split up into smaller pieces to make more focused/isolated unit tests.
- Another feature which could be implemented easily for a real-world scenario would be enabling retries on the Ktor
  client. This would make our fetching more robust and account for temporary web server failures.
- Some other interesting ideas could be turning the downloader into a CLI application or actual reusable library.

## TODOS

- GitHub Action (on each commit test suite, works easily because lightweight with mock engine)
- Dockerfiles for test web server with test files
