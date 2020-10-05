package com.vasler.bookviewer

import io.micronaut.runtime.Micronaut.*

fun main(args: Array<String>) {
	build()
	    .args(*args)
		.packages("com.vasler.bookviewer")
		.start()
}
