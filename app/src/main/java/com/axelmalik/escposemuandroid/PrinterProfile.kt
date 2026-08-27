package com.axelmalik.escposemuandroid

data class PrinterProfile(
    val id: String,
    val name: String,
    val port: Int,
    val enabled: Boolean,
)

object PrinterProfileValidator {
    fun validate(name: String, port: Int, existing: List<PrinterProfile>): String? {
        if (name.trim().isEmpty()) return "Printer name is required"
        if (port !in 1..65_535) return "Port must be between 1 and 65535"
        if (existing.any { it.port == port }) return "Port is already used"
        return null
    }
}

object PrinterProfileCodec {
    fun encode(profiles: List<PrinterProfile>): String = profiles.joinToString("\n") { profile ->
        listOf(
            escape(profile.id),
            escape(profile.name),
            profile.port.toString(),
            profile.enabled.toString(),
        ).joinToString("|")
    }

    fun decode(value: String): List<PrinterProfile> = value.lineSequence()
        .mapNotNull { decodeLine(it) }
        .toList()

    private fun decodeLine(line: String): PrinterProfile? {
        val parts = splitEscaped(line)
        if (parts.size != 4) return null
        val port = parts[2].toIntOrNull() ?: return null
        if (parts[0].isBlank() || parts[1].isBlank() || port !in 1..65_535) return null
        val enabled = when (parts[3]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return PrinterProfile(parts[0], parts[1], port, enabled)
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '|' -> append("\\|")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
    }

    private fun splitEscaped(line: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        line.forEach { character ->
            if (escaped) {
                current.append(
                    when (character) {
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> character
                    },
                )
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '|') {
                parts += current.toString()
                current.clear()
            } else {
                current.append(character)
            }
        }
        if (escaped) current.append('\\')
        parts += current.toString()
        return parts
    }
}
