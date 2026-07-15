package com.ris.imagedistributor.worker

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.local.Transmission
import com.ris.imagedistributor.data.local.TransmissionStatus
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.data.repository.DeliveryRepository
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.MasterScheduleRepository
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.GateResult
import com.ris.imagedistributor.domain.ImageSelectionEngine
import com.ris.imagedistributor.domain.SelectionUnit
import java.time.Instant
import java.time.ZoneId

/**
 * CAP-4 orchestration: a compliance gate check, then a retry pass over previously-queued items,
 * then a dispatch pass over newly-due schedule slots. A plain, constructor-injected class — not a
 * `CoroutineWorker` itself — so it is unit-testable in a JVM test without any
 * `CoroutineWorker`/`Context` machinery; `SendWorker` is a thin adapter that delegates here.
 *
 * `now` is injectable (defaults to the real wall clock) — the same testability pattern as
 * `ImageSelectionEngine`'s injectable `Random`, applied to time instead of randomness. This is
 * what makes "not yet due," "already dispatched today," and "no backfill of missed days"
 * deterministically testable.
 *
 * "No backfill of missed days" needs no special-case branch: "due" and "already dispatched today"
 * are both evaluated only against *today* (`now()`'s date) — there is no code path that ever looks
 * at yesterday's or any earlier day's schedule state.
 */
class SendDispatcher(
    private val receiverRepository: ReceiverRepository,
    private val imageRepository: ImageRepository,
    private val imageSelectionEngine: ImageSelectionEngine,
    private val transmissionRepository: TransmissionRepository,
    private val deliveryRepository: DeliveryRepository,
    private val complianceRepository: ComplianceRepository,
    private val complianceGate: ComplianceGate,
    private val masterScheduleRepository: MasterScheduleRepository,
    private val now: () -> Instant = Instant::now,
) {

    suspend fun dispatchDueSends() {
        val state = when (val result = complianceRepository.getState()) {
            // No compliance state yet (Setup never completed) or a DB failure reading it — fail
            // safe, there is nothing meaningful to gate or send yet either way.
            is AppResult.Success -> result.value ?: return
            is AppResult.Failure -> return
        }

        if (complianceGate.evaluate(state.nickname, state.city) is GateResult.Halt) return

        val receivers = when (val result = receiverRepository.getAllWithSchedules()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> throw SendDispatchException("failed to load receivers for dispatch")
        }
        val receiverById = receivers.associateBy { it.receiver.id }

        val zone = ZoneId.systemDefault()
        val nowInstant = now()
        val todayStart = nowInstant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

        retryPendingItems(receiverById, todayStart)

        // Fetched once per run, not once per receiver — app-wide, and AD-16 only requires it be
        // queried live per run, not per receiver. A failure here is fail-safe: schedule-less
        // receivers simply have nothing due this run, not a crash.
        val masterScheduleTimes = when (val result = masterScheduleRepository.getScheduleTimes()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> emptyList()
        }
        dispatchDueSlots(receivers, nowInstant, todayStart, zone, masterScheduleTimes)
    }

    private suspend fun retryPendingItems(receiverById: Map<Long, ReceiverWithSchedules>, todayStart: Instant) {
        val candidates = when (val result = transmissionRepository.getRetryCandidates()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return
        }
        for (transmission in candidates) {
            // Crossed a day boundary while still PENDING (only reachable if the device was
            // offline/Doze-suspended spanning midnight) — terminate rather than retry, so it can
            // never coexist with a freshly-enqueued transmission for today's occurrence of the
            // same slot. Consistent with this story's "no backfill of missed days" philosophy.
            if (transmission.createdAt < todayStart.toEpochMilli()) {
                transmissionRepository.update(transmission.copy(status = TransmissionStatus.FAILED.name))
                continue
            }

            // Receiver/image deleted since queuing — defensive skip, not an expected path in
            // practice (this app never deletes images/receivers with in-flight transmissions).
            val receiver = receiverById[transmission.receiverId]?.receiver ?: continue
            val image = when (val result = imageRepository.getImageById(transmission.imageId)) {
                is AppResult.Success -> result.value ?: continue
                is AppResult.Failure -> continue
            }
            attemptDelivery(receiver, image, transmission)
        }
    }

    /**
     * Two-pass by design (operator-requested redesign, 2026-07-14): pass 1 collects every due,
     * not-yet-dispatched (receiver, scheduleTime) slot across *all* receivers first, without
     * sending anything yet; pass 2 hands the whole tick's slots to
     * [ImageSelectionEngine.selectImagesForUnits] in one call, so it can coordinate counts across
     * receivers (the combined total should match the active image count) instead of each receiver
     * being decided in isolation. Pass 3 sends whatever each slot was allocated — now potentially
     * more than one image per slot, restoring the pre-Story-2.4 "one Transmission row + one
     * delivery attempt per image" shape.
     */
    private suspend fun dispatchDueSlots(
        receivers: List<ReceiverWithSchedules>,
        nowInstant: Instant,
        todayStart: Instant,
        zone: ZoneId,
        masterScheduleTimes: List<Int>,
    ) {
        val zonedNow = nowInstant.atZone(zone)
        val nowMinutesOfDay = zonedNow.hour * 60 + zonedNow.minute

        val dueUnits = mutableListOf<SelectionUnit>()
        for (receiverWithSchedules in receivers) {
            val receiver = receiverWithSchedules.receiver
            // A receiver with any schedule of its own never consults the master schedule at
            // all, even partially — all-or-nothing per receiver, never merged. [AD-16]
            val effectiveScheduleTimes = receiverWithSchedules.scheduleTimes.ifEmpty { masterScheduleTimes }
            for (scheduleTime in effectiveScheduleTimes) {
                if (scheduleTime > nowMinutesOfDay) continue // not due yet today

                val alreadyDispatched = when (
                    val result = transmissionRepository.hasDispatchedToday(receiver.id, scheduleTime, todayStart)
                ) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> continue
                }
                if (!alreadyDispatched) dueUnits.add(SelectionUnit(receiver, scheduleTime))
            }
        }
        if (dueUnits.isEmpty()) return

        val allocation = when (val result = imageSelectionEngine.selectImagesForUnits(dueUnits)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return
        }

        for (unit in dueUnits) {
            for (image in allocation[unit].orEmpty()) {
                val enqueued = when (
                    val result = transmissionRepository.enqueue(unit.receiver.id, image.id, unit.scheduleTime, nowInstant)
                ) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> continue
                }
                attemptDelivery(unit.receiver, image, enqueued)
            }
        }
    }

    private suspend fun attemptDelivery(receiver: Receiver, image: Image, transmission: Transmission) {
        val deliveryResult = deliveryRepository.send(receiver, image)
        val newAttemptCount = transmission.attemptCount + 1
        val updated = when (deliveryResult) {
            is AppResult.Success -> transmission.copy(
                status = TransmissionStatus.SENT.name,
                attemptCount = newAttemptCount,
                sentAt = now().toEpochMilli(),
            )
            is AppResult.Failure -> {
                val terminal = newAttemptCount >= TransmissionRepository.MAX_SEND_ATTEMPTS
                transmission.copy(
                    status = if (terminal) TransmissionStatus.FAILED.name else TransmissionStatus.PENDING.name,
                    attemptCount = newAttemptCount,
                )
            }
        }
        transmissionRepository.update(updated)
    }
}

class SendDispatchException(message: String) : Exception(message)
