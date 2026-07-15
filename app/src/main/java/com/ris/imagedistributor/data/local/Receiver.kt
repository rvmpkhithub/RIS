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
 *
 * [minCount]/[maxCount] — how many images this receiver gets per scheduled send, restored via
 * MIGRATION_10_11 (operator-requested field addendum, 2026-07-14) — a scheduled dispatch tick
 * coordinates every due receiver's random draw within its own [minCount, maxCount] so the
 * combined total across all of them matches the number of currently-active images. Both bounds
 * are hard: `ImageSelectionEngine.selectImagesForUnits` never allocates a receiver more than
 * [maxCount] or fewer than [minCount] (except a defensive floor-at-0 fallback if the pool
 * genuinely can't satisfy every due receiver's minimum — `ReceiverEditScreen`'s own save-time
 * validation exists specifically to keep that fallback unreachable in normal operation).
 */
@Entity(tableName = "receivers")
data class Receiver(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val channel: String,
    val phoneOrEmail: String,
    val minCount: Int = DEFAULT_MIN_COUNT,
    val maxCount: Int = DEFAULT_MAX_COUNT,
)

const val DEFAULT_MIN_COUNT = 2
const val DEFAULT_MAX_COUNT = 5

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
