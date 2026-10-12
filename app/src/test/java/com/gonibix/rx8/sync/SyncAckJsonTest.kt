package com.gonibix.rx8.sync

import org.junit.Assert.*
import org.junit.Test

class SyncAckJsonTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val sample = "00000000-0000-4000-8000-000000000002"
    private val request = PreparedRequest(SyncRequestKind.VEHICLE, id, id, id, revision = 1, payloadJson = "{}")
    private val body = """{"schemaVersion":1,"resourceId":"$id","revision":1}"""
    private fun decode(text: String, target: PreparedRequest = request) = SyncAckJson.decode(text.toByteArray(), target)

    @Test fun metadataRequiresTheExactSchemaIdRevisionAndNoExtraFields() {
        assertTrue(decode(body) is RemoteReply.MetadataAck)
        listOf(body.replace("\"revision\":1", "\"revision\":2"), body.replace(id, sample),
            body.replace("schemaVersion\":1", "schemaVersion\":2"), body.dropLast(1) + ",\"extra\":1}")
            .forEach { assertEquals(RemoteReply.InvalidAcknowledgment, decode(it)) }
    }

    @Test fun malformedAndDuplicateJsonNeverBecomesAnAcknowledgment() {
        val cases = listOf("", "$body true", body.replace("\"revision\":1", "\"revision\":1.0"),
            body.replace("\"revision\":1", "\"revision\":01"), body.replace("\"revision\":1", "\"revision\":1,\"revision\":1"),
            body.replace("resourceId", "resource\\u+049d"), body.replace("schemaVersion", "schemaVers\\u+069on"),
            body.replace("schemaVersion", "schemaVers\\u0069on").replace("\"revision\":1", "\"revision\":null"),
            body.replace("000000000001", "00000000000\\u+031"), "[".repeat(8) + "1" + "]".repeat(8))
        cases.forEach { assertEquals(it, RemoteReply.InvalidAcknowledgment, decode(it)) }
        assertEquals(RemoteReply.InvalidAcknowledgment, SyncAckJson.decode(byteArrayOf(0xC3.toByte(), 0x28), request))
        assertEquals(RemoteReply.InvalidAcknowledgment, SyncAckJson.decode(ByteArray(SyncAckJson.MAX_BYTES + 1), request))
    }

    @Test fun partialAckPreservesOnlyExplicitStoredResultsAndRejectsForeignDuplicateEmptyOrInvalidRejection() {
        val target = PreparedRequest(SyncRequestKind.TELEMETRY, id, id, id, sessionId = id,
            payloadJson = "{}", sampleIds = listOf(id, sample))
        fun reply(rows: String) = """{"schemaVersion":1,"batchId":"$id","results":[$rows]}"""
        val stored = """{"sampleId":"$sample","status":"already_stored"}"""
        val parsed = decode(reply(stored), target) as RemoteReply.BatchAck
        assertEquals(listOf(sample), parsed.acknowledgment.results.map { it.sampleId })
        listOf("", "$stored,$stored", stored.replace(sample, "00000000-0000-4000-8000-000000000003"),
            stored.replace("already_stored", "rejected"), stored.replace("already_stored", "success"))
            .forEach { assertEquals(RemoteReply.InvalidAcknowledgment, decode(reply(it), target)) }
        assertTrue(decode(reply(stored.replace("already_stored\"", "rejected\",\"code\":\"invalid_sample\"")), target) is RemoteReply.BatchAck)
    }
}
