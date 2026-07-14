package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * App-wide fallback schedule, consulted live at dispatch time by any receiver with no
 * [ReceiverSchedule] rows of its own — never copied onto a receiver. No FK to any receiver
 * (deliberately flat/app-wide). time is minutes-since-midnight, device local time, same
 * convention as [ReceiverSchedule.time]. At least 4 rows always exist here — enforced at the
 * repository layer (see MasterScheduleRepositoryImpl), unlike a receiver's own schedule which
 * may be empty. [ARCHITECTURE-SPINE.md#AD-16]
 */
@Entity(tableName = "master_schedule")
data class MasterSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Int,
)
