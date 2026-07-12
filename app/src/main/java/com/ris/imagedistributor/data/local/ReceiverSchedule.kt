package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A receiver has one or more of these — minimum 4, enforced at save time in the UI, not at the
 * schema level. time is minutes-since-midnight, device local time, same convention as the
 * single Receiver.scheduleTime field this replaces. [ARCHITECTURE-SPINE.md#Consistency Conventions]
 */
@Entity(
    tableName = "receiver_schedules",
    foreignKeys = [
        ForeignKey(
            entity = Receiver::class,
            parentColumns = ["id"],
            childColumns = ["receiverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("receiverId")],
)
data class ReceiverSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiverId: Long,
    val time: Int,
)
