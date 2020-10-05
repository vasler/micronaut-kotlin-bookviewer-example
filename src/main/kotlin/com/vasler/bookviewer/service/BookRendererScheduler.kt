package com.vasler.bookviewer.service

import com.vasler.bookviewer.util.PDFUtil
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.sql.Savepoint
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton


@Singleton
class BookRendererScheduler {
    companion object {
        private val logger: Logger = LogManager.getLogger(BookRendererScheduler::class.java)
    }

    @Inject
    @field:Named("pdfprocessor")
    private lateinit var executorService: ExecutorService

    @Value("\${bookviewer.maxWorkers}")
    lateinit var maxWorkers: Integer

    @Value("\${bookviewer.maxProcessingRetryCount}")
    lateinit var maxProcessingRetryCount: Integer

    @Inject
    private lateinit var bookStorage: BookStorageService

    @Scheduled(cron = "0/5 * 0-23 * * ?")
    fun run()
    {
        try {
            BookRenderer.start(bookStorage, maxWorkers.toInt(), maxProcessingRetryCount.toInt(), executorService)
        }
        catch (t: Throwable)
        {
            logger.error("BOOK RENDER SCHEDULER FAILED", t)
        }
    }
}

class BookRenderer(val bookStorageService: BookStorageService, val maxWorkers: Int,
                   val maxProcessingRetryCount: Int, val executorService: ExecutorService) : Runnable
{
    companion object
    {
        private val logger: Logger = LogManager.getLogger(BookRenderer::class.java)

        var lastSchedulerTick: AtomicLong = AtomicLong(0L)
        var running: Boolean = false

        fun start(bookStorage: BookStorageService, maxWorkers: Int,
                  maxProcessingRetryCount: Int, executorService: ExecutorService)
        {
            synchronized(BookRenderer.running) {
                lastSchedulerTick.set(System.currentTimeMillis())
                if (!running)   executorService.submit( BookRenderer(bookStorage, maxWorkers,
                                                        maxProcessingRetryCount, executorService))
            }
        }

        fun isScheduled() = System.currentTimeMillis() - lastSchedulerTick.get() < 10000L
    }

    override fun run()
    {
        synchronized(running)
        {
            if (running) return
            running = true
        }

        try
        {
            while (isScheduled() && renderBook()) { }
        }
        finally {
            synchronized(BookRenderer.running)
            {
                running = false
            }
        }
    }

    fun renderBook(): Boolean
    {
        // LET'S JUST CHECK IF REDIS IS ALIVE BEFORE WE CONTINUE
        bookStorageService.redisPing()

        bookStorageService.getConnection().use { conn ->

            var savepoint: Savepoint? = null
            var exception: Exception? = null
            try
            {
                val bookMetadata = bookStorageService.lockUnprocessedBook(conn) ?: return false

                bookStorageService.touchBook(bookMetadata, conn)
                savepoint = conn.setSavepoint()

                if (bookMetadata.processCount > maxProcessingRetryCount)
                {
                    logger.error("BOOK RENDERING FAILED")

                    bookMetadata.status = ProcessingStatus.FAILED.name
                    bookStorageService.updateBookMetadata(bookMetadata, conn)

                    return true
                };

                if (bookMetadata != null)
                {
                    val bookData = bookStorageService.loadBookData(bookMetadata.storageKey) ?: return true

//                        val availableProcessors = Runtime.getRuntime().availableProcessors()
                    var pagesProcessed: AtomicInteger = AtomicInteger(0)
                    val failureDetected: AtomicBoolean = AtomicBoolean(false)
                    val pageIndex: AtomicInteger = AtomicInteger(0);

                    // START TASKS
                    var futures = mutableListOf<Future<Boolean>>()
//                        for (i in 1..availableProcessors) {
                    for (i in 1..maxWorkers) {
                        try {
                            futures.add(
                                    executorService.submit( PageRenderer(pagesProcessed, failureDetected, pageIndex,
                                            bookMetadata, bookStorageService, bookData)
                                    )
                            )
                        } catch (e: Exception) {
                            logger.warn("FAILED TO START RENDERER TASK", e)
                        }
                    }

                    // WAIT FOR JOBS TO FINISH
                    for (future in futures) {
                        try {
                            (future?.get())
                        } catch (e: Exception) {
                            logger.warn("FAILED TO RETRIEVE A TASK, ", e)
                        }
                    }

                    // IF FAILURE DETECTED
                    if (failureDetected.get() || pagesProcessed.get() < bookMetadata.pageCount)
                    {
                        logger.warn("BOOK RENDER TASKS FAILED FOR BOOK ID: ${bookMetadata.id}")
                    }
                    else
                    {
                        bookMetadata.status = ProcessingStatus.PROCESSED.name
                        bookStorageService.updateBookMetadata(bookMetadata, conn)
                    }
                }
            }
            catch (e: Exception)
            {
                exception = e;

                if (savepoint != null) {
                    conn.rollback(savepoint)
                    conn.commit()
                }
                else {
                    conn.rollback()
                }

                logger.error(e.message, e)
                throw e;
            }
            finally {
                if (exception == null)
                {
                    conn.releaseSavepoint(savepoint)
                    conn.commit()
                }
            }

            return true
        }
    }
}

class PageRenderer(val pagesProcessed: AtomicInteger, val failureDetected: AtomicBoolean, val pageIndex: AtomicInteger,
                   val bookMetadata: BookMetadata, val bookStorageService: BookStorageService, val bookData: ByteArray)
    : Callable<Boolean>
{
    companion object
    {
        private val logger: Logger = LogManager.getLogger(PageRenderer::class.java)
    }

    override fun call(): Boolean
    {
        val pdfUtil = PDFUtil()
        pdfUtil.loadDoc(bookData).use { doc ->

            var pgIndex = pageIndex.getAndIncrement()
            while (pgIndex < bookMetadata.pageCount) {
                try {
                    // RENDER AND STORE THE IMAGE IF NOT FOUND
                    val imageStorageKey = "${bookMetadata.storageKey}-$pgIndex"
                    if (!bookStorageService.redisExists(imageStorageKey)) {
                        val image = PDFUtil.renderPage(pgIndex, doc)

                        if (image != null) {
                            bookStorageService.redisSetBin(imageStorageKey, image)
                            pagesProcessed.getAndIncrement()

                            logger.info("RENDERED PAGE $imageStorageKey")
                        } else {
                            failureDetected.set(true)
                        }
                    } else {
                        pagesProcessed.getAndIncrement()
                    }
                } catch (t: Throwable) {
                    failureDetected.set(true)
                }

                pgIndex = pageIndex.getAndIncrement()
            }
        }

        return true
    }
}