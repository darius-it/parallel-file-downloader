Improvement ideas (some from prev interview):
- ~~use dependency injection for Ktor client/dependencies~~
- stream files to disk!!
- use named exceptions for improved error handling, throw them from our downloader method or something like that; for example for network retries, individual chunks failing, think of some cases
- maybe do benchmarking, test various file sizes -> https://openjdk.org/projects/code-tools/jmh/

Technical notes:
Improvements:
- AppContainer is like manual DI, we create singleton objects for our dependencies HTTP Client and Logger and inject them into the Downloader
  --> Advantage: Downloader has much more loose coupling to its dependencies, we could technically completely ignore our app container and initalize the Downloader with a different HTTP Client and Logger from somewhere else

- Another advantage of AppContainer is that through lazyloading we load dependencies only when we need them, and we have one instance of each for the entire app, no overhead for dependencies, but we are still flexible to create multiple instances of the downloader with different configurations

- In a production setting it would probably make more sense to use Koin since it handles Singletons and many other situations for us, and it's also a bit nicer to test

- Even with manual DI, our tests are much nicer since we don't need to replace the instance variable of the client but can rather initialize our Downloader with a different HttpClient dependency