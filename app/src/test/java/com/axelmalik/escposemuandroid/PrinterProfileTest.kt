package com.axelmalik.escposemuandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterProfileTest {
    @Test
    fun validationRejectsBlankName() {
        val error = PrinterProfileValidator.validate("   ", 9100, emptyList())

        assertEquals("Printer name is required", error)
    }

    @Test
    fun validationRejectsInvalidAndDuplicatePorts() {
        assertEquals("Port must be between 1 and 65535", PrinterProfileValidator.validate("Kitchen", 0, emptyList()))
        assertEquals(
            "Port is already used",
            PrinterProfileValidator.validate("Kitchen", 9100, listOf(PrinterProfile("main", "Main", 9100, true))),
        )
    }

    @Test
    fun codecRoundTripPreservesEscapedProfileFields() {
        val profiles = listOf(
            PrinterProfile("main", "Main | Counter\nPrinter", 9100, true),
            PrinterProfile("kitchen", "Kitchen", 9101, false),
        )

        val decoded = PrinterProfileCodec.decode(PrinterProfileCodec.encode(profiles))

        assertEquals(profiles, decoded)
    }

    @Test
    fun codecSkipsMalformedRecords() {
        val decoded = PrinterProfileCodec.decode("bad-record\nmain|Main|9100|true")

        assertEquals(1, decoded.size)
        assertTrue(decoded.single().enabled)
    }
}
