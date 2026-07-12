package com.ris.imagedistributor.data.local

import androidx.room.Embedded
import androidx.room.Relation
import java.time.Instant

/** Room's @Relation shape for observeSentHistory() — mapped to the domain [DeliveryRecord] in the repository. */
data class TransmissionWithImage(
    @Embedded val transmission: Transmission,
    @Relation(parentColumn = "imageId", entityColumn = "id")
    val image: Image?,
) {
    /**
     * `sentAt` is expected non-null here (this relation is only ever populated from a query that
     * filters `status = 'SENT'`, where `sentAt` is always set per Transmission's own AD-13
     * invariant) — but that invariant is enforced in a different file (SendDispatcher), so this
     * skips (returns null) rather than crashing if it's ever violated, the same defensive
     * treatment as an unresolved image relation, instead of a `!!` that would take down the whole
     * list on one bad row.
     */
    fun toDomain(): DeliveryRecord? {
        val resolvedImage = image ?: return null
        val sentAtMillis = transmission.sentAt ?: return null
        return DeliveryRecord(transmissionId = transmission.id, image = resolvedImage, sentAt = Instant.ofEpochMilli(sentAtMillis))
    }
}
