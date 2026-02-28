# Parallel File Downloader

A simple tool to download files from a web server in chunks (using the `Range` header) which are downloaded in parallel. Project made for the made for the JB Internship application Summer/Fall 2026

## How to use
Prerequisite is a web server which supports downloading in chunks using the `Range` header, as outlined in the task description (so for example an Apache web server pointed to server some files to be downloaded).

The downloader logic is exposed through the `downloadFile()` method, which can be used as follows:

```kotlin
downloadFile(
    "someFileName.png", // file name to download
    "http://localhost:3210", // server URL, defaults to http://localhost:8080
    4, // # of chunks to download in parallel, defaults to 2
    true // whether to save file to disk, defaults to true
)
```

All the logic is contained inside the `downloader` package, which could theoretically be extracted into a library. 

Moreover, because the implementation uses Ktor for a HTTP Client (with the CIO engine), this could also be used in Kotlin Multiplatform projects (except saving to disk, which would need to use a library like FileKit to work properly).

## Running the tests
The tests are written using JUnit and can be run from IntelliJ or directly through Gradle. To run the tests through Gradle, use the following command in the terminal:

```bash
./gradlew test
```

## How I approached the problem
1. First, I tested out the web server launched through the Docker command to familiarize myself with file downloading and how the Range header works. For this I used Bruno (similar to Postman) to test different HTTP requests.
2. Then, I quickly wrote down the abstract flow of how the downloader should work: The downloader first does the HEAD request, takes the bytes count we get back, splits it into chunk ranges and downloads all of those chunks in parallel. Finally, we wait for all chunks to have completed downloading and put them back together (in the order that we've downloaded them).
3. With this flow in mind, I jumped into the initial prototype. Since I've previously worked with Ktor in a Compose Multiplatform project, I chose that as a technology that works well as a HTTP client and is pretty powerful and configurable. <br> This prototype focused on purely getting the main idea running quickly, so it was all inside one file with nearly no error handling. The basic concept did seem to work, so I was able to continue with this implementation.
4. To prepare for testing, I extracted the steps in the two main stages and created according data classes to enclose all data and required methods. Finally, I combined all the other bits and pieces together into a Downloader object, which provides the main `downloadFile` method which handles the entire download logic.
5. After the general implementation was done, my goal for testing was covering the main stages of the download process (so each method available in my downloader package). Because Ktor and Kotlin Coroutines do a lot of heavy lifting, at some points it was difficult to create "actual" unit tests, so you can argue they are somewhere between a unit and integration test (they use a Ktor mock engine to simulate the behaviour of our web server). <br> Nevertheless, there are tests like `testCalculateChunkRanges` which test logic in isolation and don't depend on our Ktor HTTP client.
6. Lastly, I added some more complex tests to test cases where fundamental assumptions about our web server don't apply, for example the Range header not working.

Overall, the implementation was not the difficult part, rather knowing what exactly to test and how far to go with it. While there is lots to handle and I couldn't possibly cover everything, the core logic should be reasonably robust now. 

Looking back, I probably could've structured the code a bit differently to make actual unit tests easier (or do Test-Driven Development), but my focus was on getting something that does the intended job without overcomplicating the code. (I know from past projects that the seemingly trivial task of downloading something/working with network requests can be quite tricky.)