package com.gonibix.rx8.obd

import com.gonibix.rx8.telemetry.obd.ObdPid
import com.gonibix.rx8.telemetry.obd.ObdPollingGroup

/**
 * Fair bounded round-robin lanes: fast requests never wait behind the entire extended catalog.
 * There are no queued timer ticks or claimed Hz. Every command still waits for the prior reply.
 */
internal class ObdPollingSchedule(parameters: List<ObdPid>) {
    private val lanes = parameters.distinct().filter { it.pollingGroup != ObdPollingGroup.METADATA }
        .groupBy { it.pollingGroup }
    private val positions = mutableMapOf<ObdPollingGroup, Int>()
    private val failedAttempts = mutableMapOf<ObdPid, Int>()
    private val retryAfter = mutableMapOf<ObdPid, Long>()
    private var lanePosition = 0

    fun next(nowMillis: Long): ObdPid? {
        repeat(ORDER.size) {
            val group = ORDER[lanePosition]
            lanePosition = (lanePosition + 1) % ORDER.size
            val parameters = lanes[group].orEmpty()
            repeat(parameters.size) {
                val index = positions[group] ?: 0
                val parameter = parameters[index]
                positions[group] = (index + 1) % parameters.size
                if (nowMillis >= (retryAfter[parameter] ?: Long.MIN_VALUE)) return parameter
            }
        }
        return null
    }

    fun completed(parameter: ObdPid, nowMillis: Long, failed: Boolean) {
        if (!failed) {
            failedAttempts.remove(parameter)
            retryAfter.remove(parameter)
            return
        }
        val failures = ((failedAttempts[parameter] ?: 0) + 1).coerceAtMost(6)
        failedAttempts[parameter] = failures
        retryAfter[parameter] = nowMillis + failures * 5_000L
    }

    private companion object {
        val ORDER = listOf(
            ObdPollingGroup.FAST, ObdPollingGroup.FAST, ObdPollingGroup.PRIMARY,
            ObdPollingGroup.NORMAL, ObdPollingGroup.NORMAL, ObdPollingGroup.SLOW,
        )
    }
}
