package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per queued send, created once and updated in place on retry — never a second row for
 * the same send. `sentAt` is only set once `status` becomes `SENT`. [ARCHITECTURE-SPINE.md#AD-13]
 *
 * `createdAt` is set once at insert and never updated — distinct from `sentAt`, which stays null
 * until the send actually succeeds. `scheduleTime` is the `ReceiverSchedule.time` value (minutes
 * since midnight) this batch was dispatched for — intentionally **not** a foreign key to
 * `ReceiverSchedule.id`, since `ReceiverRepositoryImpl.updateReceiver()` deletes and reinserts all
 * of a receiver's schedule rows on every save, reassigning new ids every time. Keying off the raw
 * time value instead is immune to that. Together these two columns are what let `SendWorker`
 * (Story 2.2) tell whether a given schedule slot has already been dispatched today, without which
 * it would re-select and re-send for the same slot on every ~15-minute periodic tick.
 */
@Entity(
    tableName = "transmissions",
    indices = [
        Index(value = ["receiverId", "status", "sentAt"]),
        Index(value = ["receiverId", "scheduleTime", "createdAt"]),
    ],
)
data class Transmission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiverId: Long,
    val imageId: Long,
    val status: String,
    val attemptCount: Int,
    val sentAt: Long?,
    val createdAt: Long,
    val scheduleTime: Int,
)

enum class TransmissionStatus { PENDING, SENT, FAILED }
