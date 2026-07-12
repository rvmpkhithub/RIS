package com.ris.imagedistributor.data.local

import java.time.Instant

/**
 * Domain-facing shape for a single dashboard history entry — keeps Room's @Relation/@Embedded out
 * of the repository API. `transmissionId` (not `image.id`) is the correct list-item identity: the
 * same image can legitimately appear in more than one transmission within the dashboard's 30-day
 * window (mechanics.md's 7-day exclusion is a soft preference, not a permanent one), so keying on
 * `image.id` alone would collide.
 */
data class DeliveryRecord(
    val transmissionId: Long,
    val image: Image,
    val sentAt: Instant,
)
