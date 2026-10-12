package com.gonibix.rx8.sync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, strict response parser. Server text is never exposed in logs or user errors. */
object SyncAckJson {
    const val MAX_BYTES = 131_072

    fun decode(bytes: ByteArray, request: PreparedRequest): RemoteReply {
        try {
            require(bytes.size in 1..MAX_BYTES)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = Reader(text).read() as? Map<*, *> ?: invalid()
            val schema = root.number("schemaVersion").also { require(it == 1L) }.toInt()
            val result = if (request.kind == SyncRequestKind.TELEMETRY) {
                require(root.keys == setOf("schemaVersion", "batchId", "results"))
                val entries = root["results"] as? List<*> ?: invalid()
                require(entries.size in 1..500)
                SyncResult.BatchAck(schema, root.string("batchId"), entries.map { entry ->
                    val row = entry as? Map<*, *> ?: invalid()
                    require(row.keys == setOf("sampleId", "status") || row.keys == setOf("sampleId", "status", "code"))
                    val status = when (row.string("status")) {
                        "stored" -> AckStatus.STORED
                        "already_stored" -> AckStatus.ALREADY_STORED
                        "rejected" -> AckStatus.REJECTED
                        else -> invalid()
                    }
                    val code = if (row.containsKey("code")) row.string("code") else null
                    SampleAck(row.string("sampleId"), status, code)
                })
            } else {
                require(root.keys == setOf("schemaVersion", "resourceId", "revision"))
                SyncResult.MetadataAck(schema, root.string("resourceId"), root.number("revision"))
            }
            validateAcknowledgment(request, result)
            return when (result) {
                is SyncResult.MetadataAck -> RemoteReply.MetadataAck(result)
                is SyncResult.BatchAck -> RemoteReply.BatchAck(result)
                else -> invalid()
            }
        } catch (_: Exception) {
            return RemoteReply.InvalidAcknowledgment
        }
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: invalid()
    private fun Map<*, *>.number(key: String): Long = this[key] as? Long ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid acknowledgment")

    private class Reader(private val text: String) {
        private var position = 0
        fun read(): Any {
            val result = value(0)
            whitespace()
            require(position == text.length)
            return result
        }
        private fun value(depth: Int): Any {
            require(depth <= 6)
            whitespace()
            return when (text.getOrNull(position)) {
                '{' -> {
                    position++
                    val map = linkedMapOf<String, Any>()
                    whitespace()
                    if (!take('}')) {
                        do {
                            whitespace()
                            val key = string()
                            require(key !in map && map.size < 8)
                            whitespace(); require(take(':'))
                            map[key] = value(depth + 1)
                            whitespace()
                        } while (take(','))
                        require(take('}'))
                    }
                    map
                }
                '[' -> {
                    position++
                    val list = mutableListOf<Any>()
                    whitespace()
                    if (!take(']')) {
                        do {
                            require(list.size < 500)
                            list += value(depth + 1)
                            whitespace()
                        } while (take(','))
                        require(take(']'))
                    }
                    list
                }
                '"' -> string()
                in '0'..'9' -> {
                    val start = position
                    while (text.getOrNull(position) in '0'..'9') position++
                    val number = text.substring(start, position)
                    require(number.length <= 19 && (number.length == 1 || number[0] != '0'))
                    number.toLong()
                }
                else -> invalid()
            }
        }
        private fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            while (true) {
                val char = text.getOrNull(position++) ?: invalid()
                if (char == '"') break
                if (char == '\\') {
                    val escape = text.getOrNull(position++) ?: invalid()
                    result.append(when (escape) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(position + 4 <= text.length)
                            val digits = text.substring(position, position + 4)
                            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            digits.toInt(16).toChar().also { position += 4 }
                        }
                        else -> invalid()
                    })
                } else {
                    require(char.code >= 32)
                    result.append(char)
                }
                require(result.length <= 128)
            }
            // ACK identifiers, status and codes are ASCII allowlists; Unicode is not needed.
            require(result.all { it.code in 32..126 })
            return result.toString()
        }
        private fun take(char: Char): Boolean = (text.getOrNull(position) == char).also { if (it) position++ }
        private fun whitespace() { while (text.getOrNull(position) in listOf(' ', '\r', '\n', '\t')) position++ }
    }
}
