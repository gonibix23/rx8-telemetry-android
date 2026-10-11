package com.gonibix.rx8.telemetry.obd

import com.gonibix.rx8.telemetry.vehicle.Vin
import java.util.Locale

/** ECU identifiers use the same CAN identifier / legacy source-address convention as Mode 01. */
data class VinResponse(val ecu: String, val vin: String)

/**
 * Decodes one complete 0902 prompt body with ATH1/ATS1/CAF1/D0 and automatic CAN flow control.
 * Supports the five numbered legacy packets and CAN's 20-byte Mode 09 VIN message.
 * This is not a general ISO-TP implementation. Headerless, extended-address, and long KWP
 * variants are not inferred. Every responding ECU must have one complete, ordered response.
 *
 * ELM327 datasheet, pp. 14 and 38-44: H1 exposes PCI despite CAF1, legacy headers expose a
 * checksum, and VIN layouts differ by protocol. ELM validates the legacy checksum/CRC;
 * printed DATA ERROR markers are rejected before any reconstruction.
 * https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf
 */
object ElmVinParser {
    private val whitespace = Regex("[ \\t]+")
    private val byteToken = Regex("[0-9A-F]{2}")
    private val can11Header = Regex("[0-9A-F]{3}")
    private val can29Header = Regex("[0-9A-F]{8}")

    /** Fails the complete reply on any invalid/missing ECU sequence; never returns a partial VIN. */
    fun parse(body: String, protocol: ObdProtocol): List<VinResponse> {
        val reply = ElmResponseParser.parse("0902", body)
        if (reply.errors.isNotEmpty()) {
            val reason = if (reply.errors.all { it == ElmError.NO_DATA }) ObdFailure.NO_DATA else ObdFailure.ADAPTER_ERROR
            throw ObdParseException(reason, "The adapter did not return a valid VIN response.")
        }
        if (reply.dataLines.isEmpty()) malformed("No VIN response frames were returned.")
        return if (protocol.canIdBits != null) parseCan(reply.dataLines, protocol.canIdBits) else parseLegacy(reply.dataLines, protocol)
    }

    private fun parseCan(lines: List<String>, idBits: Int): List<VinResponse> {
        val messages = linkedMapOf<String, CanMessage>()
        for (line in lines) {
            val (ecu, bytes) = canFrame(line, idBits)
            when (bytes[0] ushr 4) {
                0 -> {
                    val size = bytes[0]
                    if (size !in 1..7 || bytes.size < size + 1) malformed("Invalid CAN single-frame response.", ecu)
                    rejectNegative(bytes.subList(1, size + 1), ecu)
                    malformed("A complete VIN cannot fit in a CAN single frame.", ecu)
                }
                1 -> {
                    if (bytes.size != 8) malformed("Truncated VIN first frame.", ecu)
                    val length = ((bytes[0] and 0x0F) shl 8) or bytes[1]
                    if (length != 20) malformed("Unsupported VIN message length.", ecu)
                    if (ecu in messages) malformed("Repeated VIN first frame.", ecu)
                    val payload = bytes.drop(2)
                    if (payload.take(3) != listOf(0x49, 0x02, 0x01)) {
                        malformed("Unexpected VIN service, PID, or item count.", ecu)
                    }
                    messages[ecu] = CanMessage(payload.toMutableList())
                }
                2 -> {
                    val message = messages[ecu] ?: malformed("VIN continuation arrived before its first frame.", ecu)
                    if (message.payload.size == 20 || bytes[0] != 0x20 + message.nextSequence) {
                        malformed("Repeated or out-of-order VIN continuation.", ecu)
                    }
                    // A 20-byte VIN message occupies six bytes, then seven, then seven.
                    if (bytes.size != 8) malformed("Truncated VIN continuation.", ecu)
                    message.payload.addAll(bytes.drop(1))
                    message.nextSequence++
                }
                else -> malformed("Unsupported CAN frame in the VIN response.", ecu)
            }
        }
        return messages.map { (ecu, message) ->
            if (message.payload.size != 20 || message.nextSequence != 3) malformed("Incomplete VIN message.", ecu)
            VinResponse(ecu, decodeVin(message.payload.drop(3), ecu))
        }
    }

    private fun parseLegacy(lines: List<String>, protocol: ObdProtocol): List<VinResponse> {
        val messages = linkedMapOf<String, LegacyMessage>()
        for (line in lines) {
            val raw = bytes(line.split(whitespace))
            if (raw.size !in 7..11) malformed("Unsupported legacy VIN frame length.")
            val ecu = hexByte(raw[2])
            val payload = raw.subList(3, raw.lastIndex)
            if (protocol == ObdProtocol.KWP_FAST || protocol == ObdProtocol.KWP_SLOW) {
                if (raw[0] and 0x80 == 0 || raw[0] and 0x3F != payload.size) {
                    malformed("Unsupported legacy VIN header layout.", ecu)
                }
            }
            rejectNegative(payload, ecu)
            if (payload.size != 7 || payload[0] != 0x49 || payload[1] != 0x02) {
                malformed("Unexpected VIN service, PID, or packet length.", ecu)
            }
            val message = messages.getOrPut(ecu) { LegacyMessage() }
            if (payload[2] != message.nextPacket || message.nextPacket > 5) {
                malformed("Repeated or out-of-order legacy VIN packet.", ecu)
            }
            message.payload.addAll(payload.drop(3))
            message.nextPacket++
        }
        return messages.map { (ecu, message) ->
            if (message.nextPacket != 6 || message.payload.size != 20) malformed("Incomplete legacy VIN message.", ecu)
            if (message.payload.take(3).any { it != 0 }) malformed("Invalid legacy VIN filler bytes.", ecu)
            VinResponse(ecu, decodeVin(message.payload.drop(3), ecu))
        }
    }

    private fun canFrame(line: String, idBits: Int): Pair<String, List<Int>> {
        val tokens = line.split(whitespace)
        val headerTokens = when {
            idBits == 11 && tokens.firstOrNull()?.matches(can11Header) == true -> 1
            idBits == 29 && tokens.firstOrNull()?.matches(can29Header) == true -> 1
            idBits == 29 && tokens.size >= 4 && tokens.take(4).all(byteToken::matches) -> 4
            else -> malformed("Missing or malformed VIN CAN identifier.")
        }
        val ecu = tokens.take(headerTokens).joinToString("")
        val id = ecu.toLongOrNull(16) ?: malformed("Invalid VIN CAN identifier.")
        if (id > if (idBits == 11) 0x7FF else 0x1FFFFFFF) malformed("VIN CAN identifier exceeds its protocol range.")
        val data = bytes(tokens.drop(headerTokens), ecu)
        if (data.isEmpty() || data.size > 8) malformed("Invalid VIN CAN frame length.", ecu)
        return ecu to data
    }

    private fun decodeVin(data: List<Int>, ecu: String): String {
        // Wire data must already be uppercase ASCII. Manual-entry whitespace/case normalization
        // must not hide an invalid byte or malformed VIN received from the adapter.
        if (data.size != 17 || data.any { it !in 0x21..0x7E }) malformed("Invalid VIN characters or length.", ecu)
        val candidate = data.joinToString("") { it.toChar().toString() }
        val normalized = Vin.normalize(candidate)
        if (normalized == null || normalized != candidate) malformed("Invalid VIN characters or length.", ecu)
        return normalized
    }

    private fun rejectNegative(payload: List<Int>, ecu: String) {
        if (payload.size == 3 && payload[0] == 0x7F && payload[1] == 0x09) {
            throw ObdParseException(ObdFailure.NEGATIVE_RESPONSE, "An ECU rejected the VIN request.", ecu)
        }
    }

    private fun bytes(tokens: List<String>, ecu: String? = null): List<Int> {
        if (tokens.any { !byteToken.matches(it) }) malformed("Expected spaced hexadecimal VIN frame bytes.", ecu)
        return tokens.map { it.toInt(16) }
    }

    private fun hexByte(value: Int): String = value.toString(16).padStart(2, '0').uppercase(Locale.ROOT)
    private fun malformed(message: String, ecu: String? = null): Nothing =
        throw ObdParseException(ObdFailure.MALFORMED, message, ecu)

    private class CanMessage(val payload: MutableList<Int>, var nextSequence: Int = 1)
    private class LegacyMessage(val payload: MutableList<Int> = mutableListOf(), var nextPacket: Int = 1)
}
