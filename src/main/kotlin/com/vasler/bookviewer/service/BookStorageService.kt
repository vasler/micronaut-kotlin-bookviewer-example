package com.vasler.bookviewer.service

import com.vasler.bookviewer.util.ISBNUtil
import com.vasler.bookviewer.util.PDFUtil
import io.lettuce.core.api.StatefulRedisConnection
import io.micronaut.context.annotation.Value
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.InputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

enum class InsertionStatus
{
    SUCCESS,
    ALREADY_EXISTS,
    DOCUMENT_EMPTY,
    ISBN_INVALID,
    USER_NOT_FOUND
}

enum class ProcessingStatus
{
    UNPROCESSED,
    PROCESSED,
    FAILED
}

enum class PageStatus
{
    UNPROCESSED,
    DOES_NOT_EXIST
}

data class BookMetadata(var id: Long, var userId: Long, var isbn: String, var storageKey: String, var pageCount: Int,
                        var processCount: Int, var status: String, var updateTs: Date, var createTs: Date)

@Singleton
class BookStorageService
{
    companion object
    {
        private val logger: Logger = LogManager.getLogger(BookStorageService::class.java)

        const val SCHM = "bookviewer"
    }

//    JDBC WITH INJECTION DOES NOT WORK FOR SOME REASON,
//    HAD TO MANUALLY OPEN THE CONNECTION
//    @Inject lateinit var ds: DataSource

    @Value("\${bookviewer.processingRetryInterval}")
    lateinit var processingRetryInterval: Integer

    @Inject
    private lateinit var redis: StatefulRedisConnection<String, String>

    fun redisSet(key: String, value: String) = redis.sync().set(key, value) == "OK"

    fun redisSetBin(key: String, value: ByteArray) = redis.sync().set(key, Base64.getEncoder().encodeToString(value)) == "OK"

    fun redisGet(key: String): String? = redis.sync().get(key)

    fun redisGetBin(key: String): ByteArray?
    {
        val value = redis.sync().get(key)
        return if (value != null && value.isNotEmpty()) Base64.getDecoder().decode(value) else null
    }

    fun redisExists(key: String) = redis.sync().exists(key) > 0;

    fun redisDel(key: String): Boolean {
        return redis.sync().del(key) > 0
    }

    fun redisPing() = redis.sync().ping()

    @Value("\${datasources.default.url}")
    lateinit var dsURL: String

    @Value("\${datasources.default.username}")
    lateinit var dsUsername: String

    @Value("\${datasources.default.password}")
    lateinit var dsPassword: String

    fun getConnection(): Connection
    {
        val conn = DriverManager.getConnection(dsURL, dsUsername, dsPassword)
        conn.autoCommit = false

        return conn
    }

    fun createRedisKey(username: String, isbn: String, normalized: Boolean = false)
            = "PDF-$username-${if (!normalized) ISBNUtil.normalize(isbn) ?: throw Exception("INVALID ISBN") else isbn}"

    data class BookListMetadata(var isbn: String, var pageCount: Int)
    data class BookList(var firstItemIndex: Int, var pageSize: Int, val books: List<BookListMetadata>)

    fun listBooks(username: String, offset: Int, limit: Int = 50): BookList
    {
        val books = mutableListOf<BookListMetadata>()

        val validatedOffset = if (offset < 0) 0 else offset
        val validatedLimit = if (limit !in 1..100) 100 else limit

        getConnection().use { conn ->
            val ps = conn.prepareStatement("SELECT isbn, page_count FROM $SCHM.book WHERE user_id = " +
                    "(SELECT id FROM $SCHM.user WHERE username = ?) ORDER BY creation_ts LIMIT ? OFFSET ?")

            ps.setString(1, username)
            ps.setInt(2, validatedLimit)
            ps.setInt(3, validatedOffset)

            val rs = ps.executeQuery()
            while (rs.next())   books.add(BookListMetadata(rs.getString(1), rs.getInt(2)))

            conn.rollback();
        }

        return BookList(validatedOffset, validatedLimit, books)
    }

    fun insertBook(isbn: String, dataStream: InputStream, username: String): InsertionStatus
    {
        val normalizedISBN = ISBNUtil.normalize(isbn) ?: return InsertionStatus.ISBN_INVALID

        getConnection().use { conn ->

            try
            {
                if (findByISBN(normalizedISBN, username, conn) != null) {
                    conn.rollback()
                    return InsertionStatus.ALREADY_EXISTS
                }

                val data = dataStream.readAllBytes()

                val pdfUtil = PDFUtil()
                pdfUtil.loadDoc(data).use { doc ->

                    val pageCount = doc.numberOfPages
                    if (pageCount == 0)
                    {
                        conn.rollback()
                        return InsertionStatus.DOCUMENT_EMPTY
                    }

                    val seqRs = conn.prepareStatement("SELECT nextval('$SCHM.book_pk_seq')").executeQuery();
                    seqRs.next()
                    val bookId = seqRs.getBigDecimal(1)

                    val userId = getUserId(username, conn)

                    if (userId == null) {
                        conn.rollback()
                        return InsertionStatus.USER_NOT_FOUND
                    }

                    val storageKey = createRedisKey(username, normalizedISBN, true)
                    val bookPs = conn.prepareStatement(
                            "INSERT INTO $SCHM.book (id, user_id, isbn, storage_key, page_count, status)" +
                                    " VALUES (?, ?, ?, ?, ?, ?)")

                    bookPs.setBigDecimal(1, bookId)
                    bookPs.setBigDecimal(2, userId)
                    bookPs.setString(3, normalizedISBN)
                    bookPs.setString(4, storageKey)
                    bookPs.setInt(5, pageCount)
                    bookPs.setString(6, ProcessingStatus.UNPROCESSED.name)
                    bookPs.executeUpdate()

                    redisSetBin(storageKey, data)
                }

                conn.commit()
            }
            catch (t: Throwable) {
                conn.rollback()
                logger.error(t.message, t)

                throw t
            }
        }

        return InsertionStatus.SUCCESS
    }

    fun loadPage(isbn: String, page: Int, username: String): ByteArray?
    {
        getConnection().use { conn ->
            val normISBN = ISBNUtil.normalize(isbn) ?: return null
            val bookId = findByISBN(normISBN, username, conn) ?: return null
            val bookMetadata = loadBookMetadata(bookId, conn) ?: return null

            return redisGetBin("${bookMetadata.storageKey}-$page")
        }
    }

    fun loadPage(bookId: Long, page: Int): ByteArray?
    {
        getConnection().use { conn ->
            val bookMetadata = loadBookMetadata(bookId, conn) ?: return null

            return redisGetBin("${bookMetadata.storageKey}-$page")
        }
    }

    fun getExpirablePageURL(isbn: String, page: Int, username: String): String?
    {
        getConnection().use { conn ->
            val normISBN = ISBNUtil.normalize(isbn) ?: return null
            val bookId = findByISBN(normISBN, username, conn) ?: return null
            val bookMetadata = loadBookMetadata(bookId, conn) ?: return null

            val exists = redisExists("${bookMetadata.storageKey}-$page")

            return if (exists) "$bookId:$page" else null
        }
    }

    fun loadBookMetadata(bookId: Long, conn: Connection): BookMetadata?
    {
        val ps = conn.prepareStatement("SELECT " +
                "id, " +
                "user_id, " +
                "isbn, " +
                "storage_key, " +
                "page_count, " +
                "process_count, " +
                "status, " +
                "update_ts, " +
                "creation_ts " +
                "FROM $SCHM.book " +
                "WHERE id = ?")

        ps.setLong(1, bookId)
        val rs = ps.executeQuery()

        return if (rs.next())
            BookMetadata(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getInt(5), rs.getInt(6),
                    rs.getString(7), rs.getTimestamp(8), rs.getTimestamp(9))
        else null
    }
    
    fun loadBookData(storageKey: String): ByteArray?
    {
        return redisGetBin(storageKey)
    }

    fun lockUnprocessedBook(conn: Connection): BookMetadata?
    {
        val ps = conn.prepareStatement("SELECT " +
                "id, " +
                "user_id, " +
                "isbn, " +
                "storage_key, " +
                "page_count, " +
                "process_count, " +
                "status, " +
                "update_ts, " +
                "creation_ts " +
                "FROM $SCHM.book " +
                "WHERE status = '${ProcessingStatus.UNPROCESSED.name}' " +
                "AND (" +
                "update_ts < current_timestamp - INTERVAL '${processingRetryInterval.toInt()} second' " +
                "OR creation_ts = update_ts" +
                ") " +
                "FOR UPDATE SKIP LOCKED LIMIT 1")

        val rs = ps.executeQuery()

        return if (rs.next())
            BookMetadata(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getInt(5), rs.getInt(6),
                    rs.getString(7), rs.getTimestamp(8), rs.getTimestamp(9))
            else null
    }

    fun loadProcessCountAndUpdateTs(bookMetadata: BookMetadata, conn: Connection)
    {
        val selectPs = conn.prepareStatement("SELECT process_count, update_ts FROM $SCHM.book WHERE id = ?")
        selectPs.setLong(1, bookMetadata.id)

        val rs = selectPs.executeQuery()
        if (rs.next())
        {
            bookMetadata.processCount = rs.getInt(1)
            bookMetadata.updateTs = rs.getTimestamp(2)
        }
    }

    fun touchBook(bookMetadata: BookMetadata, conn: Connection)
    {
        val ps = conn.prepareStatement("UPDATE $SCHM.book SET process_count = process_count + 1, " +
                "update_ts = current_timestamp WHERE id = ?")

        ps.setLong(1, bookMetadata.id)
        ps.executeUpdate()

        loadProcessCountAndUpdateTs(bookMetadata, conn)
    }

    fun updateBookMetadata(bookMetadata: BookMetadata, conn: Connection)
    {
        val ps = conn.prepareStatement("UPDATE $SCHM.book SET " +
                "user_id = ?, " +
                "isbn = ?, " +
                "storage_key = ?, " +
                "page_count = ?, " +
                "process_count = ?, " +
                "status = ?, " +
                "update_ts = current_timestamp " +
                "WHERE id = ?")

        with (bookMetadata) {
            ps.setLong(1, userId)
            ps.setString(2, isbn)
            ps.setString(3, storageKey)
            ps.setInt(4, pageCount)
            ps.setInt(5, processCount)
            ps.setString(6, status)
            ps.setLong(7, id)
        }

        ps.executeUpdate()

        loadProcessCountAndUpdateTs(bookMetadata, conn)
    }

    private fun findByISBN(isbn: String, username: String, conn: Connection): Long?
    {
        val ps = conn.prepareStatement("SELECT id FROM $SCHM.book WHERE isbn = ? " +
                "AND user_id = (SELECT id FROM $SCHM.user WHERE username = ?)")

        ps.setString(1, isbn)
        ps.setString(2, username)

        val rs = ps.executeQuery()
        return if (rs.next()) rs.getLong(1) else null
    }

    private fun getUserId(username: String, conn: Connection): BigDecimal
    {
        val ps = conn.prepareStatement("SELECT id FROM $SCHM.user WHERE username = ?")
        ps.setString(1, username)
        val rs = ps.executeQuery()

        if (rs.next())
        {
            return rs.getBigDecimal(1)
        }
        else
        {
            val seqRs = conn.prepareStatement("SELECT nextval('${SCHM}.user_pk_seq')").executeQuery()
            seqRs.next()
            val userSeq = seqRs.getBigDecimal(1)

            val insertPs = conn.prepareStatement("INSERT INTO $SCHM.user (id, username, status) VALUES (?, ?, ?)");
            insertPs.setBigDecimal(1, userSeq)
            insertPs.setString(2, username)
            insertPs.setString(3, "ACTIVE")
            insertPs.executeUpdate()

            return userSeq
        }
    }
}