package com.firerpa.runtime

data class CommandResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
)

