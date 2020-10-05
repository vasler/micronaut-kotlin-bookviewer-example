package com.vasler.bookviewer.util

class ISBNUtil {
    companion object {

        fun normalize(isbn: String): String?
        {
            var normalizedISBN = isbn.replace("-", "")

            if (normalizedISBN.length == 13)
            {
                var sum = 0
                for (i in 0..11)
                {
                    if (!normalizedISBN[i].isDigit()) return null

                    val mult = if (i % 2 == 0) 1 else 3
                    sum += Character.getNumericValue(normalizedISBN[i]) * mult
                }

                var checksum = 10 - (sum % 10)
                if (checksum == 10) checksum = 0

                if (checksum == Character.getNumericValue(normalizedISBN[12]))  return normalizedISBN
            }

            return null
        }
    }
}