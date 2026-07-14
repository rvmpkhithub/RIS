package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * CAP-3 selection algorithm, mechanics.md#Selection algorithm — [AD-10]
 *
 * Called once per scheduled send (however many times a day a receiver has, per Story 1.3's
 * multi-schedule rework); has no awareness of which schedule slot triggered it — that dispatch
 * belongs to SendWorker (Story 2.2), not here.
 *
 * `random` is constructor-injected (defaults to Random.Default in production) specifically so
 * tests can substitute a deterministic source — every random draw in this class must go through
 * it, never a bare `Random.Default`/`(a..b).random()` call inline.
 */
class ImageSelectionEngine(
    private val imageRepository: ImageRepository,
    private val transmissionRepository: TransmissionRepository,
    private val random: Random = Random.Default,
) {

    suspend fun selectImageFor(receiverId: Long): AppResult<Image?> {
        val active = when (val result = imageRepository.getActiveImages()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        if (active.isEmpty()) return AppResult.Success(null) // step 4 — nothing to select, no query needed

        val since = Instant.now().minus(7, ChronoUnit.DAYS)
        val excludedIds = when (val result = transmissionRepository.getRecentlySentImageIds(receiverId, since)) {
            is AppResult.Success -> result.value.toSet()
            is AppResult.Failure -> return result
        }

        // Step 1: eligible pool = active minus anything sent to this receiver in the last 7 days.
        val eligible = active.filterNot { it.id in excludedIds }
        // Step 2/3: prefer the eligible pool; if it's empty (every active image already sent
        // within 7 days), fall back to allowing a repeat from the full active pool.
        val pool = eligible.ifEmpty { active }

        return AppResult.Success(pool.random(random))
    }
}
