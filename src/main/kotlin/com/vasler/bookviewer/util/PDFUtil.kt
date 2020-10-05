package com.vasler.bookviewer.util

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.imageio.ImageIO

class PDFUtil {
    companion object
    {
        private val logger: Logger = LogManager.getLogger(PDFUtil::class.java)

        fun renderPage(pageIndex: Int, doc: PDDocument): ByteArray?
        {
            if (pageIndex < doc.numberOfPages)
            {
                val renderer = PDFRenderer(doc)
                val pageImage = renderer.renderImageWithDPI(pageIndex, 192F )

                val baos = ByteArrayOutputStream()
                ImageIO.write(pageImage, "JPEG", baos)

                return baos.toByteArray()
            }

            return null
        }
    }

    fun printMetadata(data: InputStream)
    {
        var doc : PDDocument? = null
        try {
            doc = PDDocument.load(data)

            logger.info("Number of pages: ${doc.numberOfPages}")
        }
        catch (e: IOException)
        {
            logger.info("FAILED TO READ THE DOCUMENT")
        }
        finally
        {
            if (doc != null)
            {
                doc.close();
            }
        }
    }

    fun loadDoc(data: InputStream) = loadDoc(data.readAllBytes())

    fun loadDoc(data: ByteArray): PDDocument
    {
        return PDDocument.load(data)
    }


}