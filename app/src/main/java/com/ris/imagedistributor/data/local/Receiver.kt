package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * channel is stored as the enum name ("WHATSAPP"/"EMAIL") in a plain String column — no Room
 * TypeConverter needed, keeps the migration SQL simple (TEXT NOT NULL). Use [ReceiverChannel]
 * at call sites for type safety, converting via .name / valueOf() at the boundary.
 *
 * Schedule times live in [ReceiverSchedule] (one-to-many) — a receiver has one or more,
 * minimum 4, enforced at save time. [ARCHITECTURE-SPINE.md#Consistency Conventions]
 */
@Entity(tableName = "receivers")
data class Receiver(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val channel: String,
    val phoneOrEmail: String,
)

/** Combined shape the repository/UI work with — a receiver plus its (ordered) schedule times. */
data class ReceiverWithSchedules(
    val receiver: Receiver,
    val scheduleTimes: List<Int>,
)

enum class ReceiverChannel { WHATSAPP, EMAIL }

/**
 * Safe parse for [Receiver.channel] — [Review][Patch] fix: a raw `.valueOf(...)` call anywhere
 * a `channel` string reaches the UI would crash on a corrupted/unrecognized row. Defaults to
 * WHATSAPP rather than throwing.
 */
fun Receiver.channelOrDefault(): ReceiverChannel =
    runCatching { ReceiverChannel.valueOf(channel) }.getOrDefault(ReceiverChannel.WHATSAPP)
