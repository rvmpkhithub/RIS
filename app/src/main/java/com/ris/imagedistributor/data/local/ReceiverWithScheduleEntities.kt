package com.ris.imagedistributor.data.local

import androidx.room.Embedded
import androidx.room.Relation

/** Room's @Relation shape for observeAllWithSchedules() — mapped to the domain [ReceiverWithSchedules] in the repository. */
data class ReceiverWithScheduleEntities(
    @Embedded val receiver: Receiver,
    @Relation(parentColumn = "id", entityColumn = "receiverId")
    val schedules: List<ReceiverSchedule>,
) {
    fun toDomain(): ReceiverWithSchedules =
        ReceiverWithSchedules(receiver = receiver, scheduleTimes = schedules.map { it.time }.sorted())
}
