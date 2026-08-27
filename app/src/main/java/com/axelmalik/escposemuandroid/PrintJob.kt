package com.axelmalik.escposemuandroid

data class PrintJob(
    val id: String,
    val number: Int,
    val events: List<ParserEvent>,
)
