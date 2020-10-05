package com.vasler.bookviewer.controller

import com.vasler.bookviewer.service.BookStorageService
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.security.Principal
import java.util.*
import javax.inject.Inject


@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller("/books")
class BookController(val embeddedServer: EmbeddedServer) {
    companion object {
        private val logger: Logger = LogManager.getLogger(BookController::class.java)
    }

    @Inject
    private lateinit var bookStorageService: BookStorageService

    @Value("\${bookviewer.pageJwtKey}")
    lateinit var pageJwtKey: String


    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/upload")
    fun upload(isbn: String, file: CompletedFileUpload, principal: Principal): MutableHttpResponse<String>?
    {
        try
        {
            val insertionStatus = bookStorageService.insertBook(isbn, file.inputStream, principal.name)
            return HttpResponse.ok("{\"status\": \"${insertionStatus.name}\"}").contentType(MediaType.APPLICATION_JSON)
        }
        catch (t: Throwable)
        {
            logger.error(t.message, t)
            return HttpResponse.serverError()
        }
    }

    @Get("/list")
    fun list(nextItemIndex: Int, pageSize: Int = 50, principal: Principal): BookStorageService.BookList
    {
        return bookStorageService.listBooks(principal.name, nextItemIndex, pageSize)
    }

    @Get("/page/image/{isbn}/{page}")
    fun loadPage(isbn: String, page: Int, principal: Principal): MutableHttpResponse<ByteArray>?
    {
        val pageImage = bookStorageService.loadPage(isbn, page, principal.name)

        if (pageImage != null) {
            return HttpResponse.ok(pageImage).contentType(MediaType.IMAGE_JPEG).contentLength(pageImage.size.toLong())
        }

        return HttpResponse.badRequest()
    }

    @Get("/page/url/create/{isbn}/{page}")
    fun expirablePage(isbn: String, page: Int, principal: Principal): MutableHttpResponse<String>? {

//        var key: Key = Keys.secretKeyFor(SignatureAlgorithm.HS512)
//        var encodedKey = Base64.getEncoder().encodeToString(key.encoded)
//        logger.info("key size =${key.encoded.size} ,encodedKey = $encodedKey")

        val pageIdentifier = bookStorageService.getExpirablePageURL(isbn, page, principal.name) ?:
            return HttpResponse.badRequest("Page not available")

        val bookId = pageIdentifier.split(":")[0]
        val pageId = pageIdentifier.split(":")[1]

        var decodedKey = Base64.getDecoder().decode(pageJwtKey)
        val key = Keys.hmacShaKeyFor(decodedKey)

        val expiryCalendar = Calendar.getInstance()
        expiryCalendar.add(Calendar.MINUTE, 5)

        val jws = Jwts.builder()
                .claim("bookId", "$bookId")
                .claim("page", "$pageId")
                .signWith(key)
                .setExpiration(expiryCalendar.time)
                .compact()

        return HttpResponse.ok(
                "{\"url\": \"http://${embeddedServer.host}:${embeddedServer.port}/books/page/url/get/$jws\"}")

    }

    @Get("/page/url/get/{jws}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun loadPageURL(jws: String): MutableHttpResponse<ByteArray>?
    {
        var decodedKey = Base64.getDecoder().decode(pageJwtKey)
        val key = Keys.hmacShaKeyFor(decodedKey)

        try {
            val jwsReceived = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(jws);

            val bookId = jwsReceived.body["bookId"].toString().toLong()
            val page = jwsReceived.body["page"].toString().toInt()

            val pageImage = bookStorageService.loadPage(bookId, page)

            if (pageImage != null) {
                return HttpResponse.ok(pageImage)
                        .contentType(MediaType.IMAGE_JPEG)
                        .contentLength(pageImage.size.toLong())
            }
        }
        catch (e: JwtException)
        {

        }

        return HttpResponse.badRequest()
    }
}