package com.firerpa.runtime

import kotlin.random.Random

object DirectoryPrefixGenerator {
    private const val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val prefixLength = 6

    fun generate(): String = buildString(prefixLength) {
        repeat(prefixLength) {
            append(alphabet[Random.nextInt(alphabet.length)])
        }
    }
}
