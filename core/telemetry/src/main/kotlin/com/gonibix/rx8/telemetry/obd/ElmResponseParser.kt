package com.gonibix.rx8.telemetry.obd

import java.util.Locale

/**
 * Parses one complete ELM prompt body. The byte transport/engine owns fragmentation and timeouts.
 * Requires ATH1/ATS1, default CAF1, and DLC display off. No headerless source inference is performed.
 * These Mode 01 replies fit in a single CAN frame; arbitrary ISO-TP/VIN assembly is deferred.
 */
object ElmResponseParser {
    private val whitespace = Regex("[ \\t]+")
    private val byteToken = Regex("[0-9A-F]{2}")
    private val searchingPrefix = Regex("^SEARCHING\\.*\\s*")
    private val busInitSuccess = Regex("^BUS\\s+INIT:\\s*\\.*\\s*OK\\s*")
    private val busInitPending = Regex("^BUS\\s+INIT:\\s*\\.*\\s*$")

    /** Retains unknown text for strict command-specific validation; only known statuses are removed. */
    fun parse(command: String, body: String): ElmReply {
        if (body.length > 65_536 || body.any { it.code !in 0x20..0x7E && it !in "\r\n\t" }) {
            malformed("The adapter response is too long or contains non-ASCII control characters.")
        }
        val text = body.trim().removeSuffix(">").trim()
        if ('>' in text) malformed("More than one prompt or data after the prompt was received.")
        val echo = compact(command)
        val dataLines = mutableListOf<String>()
        val errors = mutableListOf<ElmError>()
        var searching = false
        var sawOk = false
        for (original in text.split(Regex("[\\r\\n]+"))) {
            var line = original.trim().uppercase(Locale.ROOT)
            if (line.isEmpty() || compact(line) == echo) continue
            if (searchingPrefix.containsMatchIn(line)) {
                searching = true
                line = line.replaceFirst(searchingPrefix, "").trim()
            }
            if (line.isEmpty()) continue
            val error = errorOf(line)
            if (error != null) {
                errors += error
                continue
            }
            if (busInitSuccess.containsMatchIn(line)) {
                line = line.replaceFirst(busInitSuccess, "").trim()
            } else if (busInitPending.matches(line)) {
                continue
            }
            if (line.isEmpty()) continue
            if (line == "OK") sawOk = true else dataLines += line
        }
        return ElmReply(dataLines.toList(), errors.toList(), searching, sawOk && errors.isEmpty() && dataLines.isEmpty())
    }

    fun parseProtocol(body: String): ObdProtocol {
        val reply = parse("ATDPN", body)
        rejectErrors(reply)
        if (reply.dataLines.size != 1) malformed("Expected one ATDPN protocol number.")
        val value = compact(reply.dataLines.single())
        if (!Regex("A?[0-9A-C]").matches(value)) malformed("Malformed ATDPN protocol number.")
        val code = if (value.length == 2) value.substring(1) else value
        return ObdProtocol.entries.singleOrNull { it.code == code }
            ?: throw ObdParseException(
                ObdFailure.UNSUPPORTED_PROTOCOL,
                "The adapter reported unsupported or unresolved protocol $value.",
            )
    }

    fun parseMode01(body: String, protocol: ObdProtocol, pid: Int): List<ObdResponse> {
        val expectedBytes = expectedDataBytes(pid)
        val command = "01" + hexByte(pid)
        val reply = parse(command, body)
        rejectErrors(reply)
        if (reply.dataLines.isEmpty()) malformed("No Mode 01 response frame was returned.")
        val byEcu = linkedMapOf<String, ObdResponse>()
        for (line in reply.dataLines) {
            val (ecu, payload) = when (protocol.canIdBits) {
                11, 29 -> canPayload(line, protocol.canIdBits)
                else -> legacyPayload(line, protocol)
            }
            if (payload.size == 3 && payload[0] == 0x7F && payload[1] == 0x01) {
                throw ObdParseException(
                    ObdFailure.NEGATIVE_RESPONSE,
                    "ECU $ecu rejected Mode 01 (response code ${hexByte(payload[2])}).",
                    ecu,
                )
            }
            if (payload.size - 2 !in expectedBytes || payload[0] != 0x41 || payload[1] != pid) {
                malformed("Unexpected service, PID, or data length in ECU $ecu response to $command.", ecu)
            }
            val response = ObdResponse(ecu, pid, payload.drop(2))
            val previous = byEcu[ecu]
            if (previous != null && previous != response) {
                malformed("Conflicting responses from ECU $ecu to $command.", ecu)
            }
            byEcu[ecu] = response
            if (byEcu.size > 32) malformed("Too many ECU sources in one response.")
        }
        return byEcu.values.toList()
    }

    /** MSB maps to base+1; the final bit maps to base+32 (the next capability block). */
    fun supportedPids(response: ObdResponse): Set<Int> {
        if (!isCapabilityPid(response.pid) || response.data.size != 4 || response.data.any { it !in 0..255 }) {
            malformed("Expected a four-byte supported-PID bitmap.", response.ecu)
        }
        return buildSet {
            response.data.forEachIndexed { byteIndex, value ->
                for (bit in 7 downTo 0) {
                    val pid = response.pid + byteIndex * 8 + (8 - bit)
                    if (value and (1 shl bit) != 0 && pid <= 0xFF) add(pid)
                }
            }
        }
    }

    private fun canPayload(line: String, idBits: Int): Pair<String, List<Int>> {
        val tokens = line.split(whitespace)
        val headerTokens = when {
            idBits == 11 && tokens.firstOrNull()?.matches(Regex("[0-9A-F]{3}")) == true -> 1
            idBits == 29 && tokens.firstOrNull()?.matches(Regex("[0-9A-F]{8}")) == true -> 1
            idBits == 29 && tokens.size >= 4 && tokens.take(4).all(byteToken::matches) -> 4
            else -> malformed("Missing or malformed $idBits-bit CAN header.")
        }
        val ecu = tokens.take(headerTokens).joinToString("")
        val id = ecu.toLongOrNull(16) ?: malformed("Invalid CAN identifier.")
        if (id > if (idBits == 11) 0x7FF else 0x1FFFFFFF) malformed("CAN identifier exceeds $idBits bits.")
        val bytes = bytes(tokens.drop(headerTokens), ecu)
        if (bytes.isEmpty() || bytes.size > 8) malformed("Invalid CAN frame length.", ecu)
        val pci = bytes[0]
        if (pci ushr 4 != 0 || pci !in 2..7) {
            malformed("Only ISO-TP single-frame Mode 01 replies are supported.", ecu)
        }
        if (bytes.size < pci + 1) malformed("Truncated CAN single-frame payload.", ecu)
        return ecu to bytes.subList(1, pci + 1).toList()
    }

    private fun legacyPayload(line: String, protocol: ObdProtocol): Pair<String, List<Int>> {
        val raw = bytes(line.split(whitespace))
        if (raw.size !in 7..11) malformed("Expected a three-byte legacy header, data, and checksum.")
        // The source address is stable; KWP's first header byte changes with payload length.
        val ecu = hexByte(raw[2])
        val payload = raw.subList(3, raw.lastIndex).toList()
        if (protocol == ObdProtocol.KWP_FAST || protocol == ObdProtocol.KWP_SLOW) {
            // Current short KWP replies encode payload length in the six low format-byte bits.
            // Separate length bytes or headerless KWP layouts are deliberately not inferred.
            if (raw[0] and 0x80 == 0 || raw[0] and 0x3F != payload.size) {
                malformed("Unsupported or inconsistent KWP header length.", ecu)
            }
        }
        // ELM327 H1 exposes this final byte; the adapter validates the checksum/CRC.
        // Do not apply an ISO additive checksum to J1850's CRC. Reject DATA ERROR (including
        // inline <DATA ERROR) before reaching here. CAN checksum bytes are never printed.
        // Source: ELM327 datasheet, OBD Message Formats / Error Messages and Alerts.
        return ecu to payload
    }

    private fun bytes(tokens: List<String>, ecu: String? = null): List<Int> {
        if (tokens.any { !byteToken.matches(it) }) malformed("Expected spaced hexadecimal bytes.", ecu)
        return tokens.map { it.toInt(16) }
    }

    private fun expectedDataBytes(pid: Int): IntRange =
        if (isCapabilityPid(pid)) 4..4 else ObdPid.entries.singleOrNull { it.pid == pid }
            ?.let { it.minimumDataBytes..it.dataBytes }
            ?: malformed("This Mode 01 PID is outside the implemented decoder set.")

    private fun isCapabilityPid(pid: Int): Boolean = pid in 0..0xE0 && pid % 0x20 == 0

    private fun rejectErrors(reply: ElmReply) {
        if (reply.errors.isEmpty()) return
        val reason = if (reply.errors.all { it == ElmError.NO_DATA }) ObdFailure.NO_DATA else ObdFailure.ADAPTER_ERROR
        throw ObdParseException(reason, "Adapter response: ${reply.errors.joinToString { it.name.replace('_', ' ') }}.")
    }

    private fun errorOf(line: String): ElmError? {
        val value = compact(line)
        return when {
            value == "NODATA" -> ElmError.NO_DATA
            value == "UNABLETOCONNECT" -> ElmError.UNABLE_TO_CONNECT
            value.startsWith("BUSINIT:") && value.contains("ERROR") -> ElmError.BUS_INIT_ERROR
            value == "BUSERROR" || value == "BUSBUSY" -> ElmError.BUS_ERROR
            value == "CANERROR" -> ElmError.CAN_ERROR
            value.contains("DATAERROR") -> ElmError.DATA_ERROR
            value == "BUFFERFULL" -> ElmError.BUFFER_FULL
            value == "STOPPED" -> ElmError.STOPPED
            value == "?" -> ElmError.UNKNOWN_COMMAND
            value == "LVRESET" || value == "LOWVOLTAGE" -> ElmError.LOW_VOLTAGE
            value.contains("ERROR") || value.matches(Regex("ERR[0-9A-F]{2}")) || value == "ACTALERT" -> ElmError.OTHER_ERROR
            else -> null
        }
    }

    private fun compact(value: String): String = value.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
    private fun hexByte(value: Int): String = value.toString(16).padStart(2, '0').uppercase(Locale.ROOT)
    private fun malformed(message: String, ecu: String? = null): Nothing =
        throw ObdParseException(ObdFailure.MALFORMED, message, ecu)
}
