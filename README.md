# Micronaut Book Viewer

This repository contains a small exercise Micronaut app, written in Kotlin, which provides
uploading of books in PDF format with valid ISBNs and retrieval of pages as JPEG images
after the book has been processed in the background.

Images can be retrieved by the authenticated user which uploaded the document or by
an unauthenticated user by creating the URL with expirable JSON Web Token.

The app uses [PostgreSQL](https://www.postgresql.org/) to store the metadata,
[Redis](https://redis.io/) to store PDF documents and JPEG images,
[Flyway](https://flywaydb.org/) to create the database schema on app startup,
[Apache PDFBox](https://pdfbox.apache.org/) for PDF to JPEG conversion,
[Gradle](https://gradle.org/) to build the app and
[Docker](https://docs.docker.com/) to run PostgreSQL and Redis servers.

## Building and running

- after pulling the repository to *bookviewer* directory build the app using Gradle.

```
cd bookviewer
./gradlew build
```

- run the Docker Compose to download and run PostgreSQL and Redis Docker images.
```
docker-compose up
```
or
```
docker-compose up -d
```
to run it in the background.

- run the app
```
./gradlew run
```

## Usage

There is no fronted available, for testing you can use the provided Postman collection.
- Login
    - submit credentials in body of the http request
    - any credentials will be accepted
    - the returned JWT token is stored in Postman's globals to be used by other requests
- List Books
    - get a paginated book (ISBN) list for the currently authenticated user
- Upload Book
    - upload a book in PDF format with a correct ISBN
    - multipart/form-data POST request is used to submit the book
- Get Page Image
    - get the image of a page for a book by providing the ISBN and page number (starting from 0)
    - the user has to be authenticated
- Create Expirable Page Image URL
    - create an expirable URL to a page image which can be used by an unauthenticated user to view the page

## License

Distributed under the [MIT License](LICENSE).