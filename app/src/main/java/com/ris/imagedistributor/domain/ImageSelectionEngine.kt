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

    suspend fun selectImagesFor(receiverId: Long, minCount: Int, maxCount: Int): AppResult<List<Image>> {
        if (minCount < 0 || maxCount < minCount || maxCount == Int.MAX_VALUE) {
            return AppResult.Failure(FailureReason.INVALID_INPUT)
        }

        val active = when (val result = imageRepository.getActiveImages()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }

        val since = Instant.now().minus(7, ChronoUnit.DAYS)
        val excludedIds = when (val result = transmissionRepository.getRecentlySentImageIds(receiverId, since)) {
            is AppResult.Success -> result.value.toSet()
            is AppResult.Failure -> return result
        }

        // Step 1: random count Z, re-rolled fresh every call.
        val z = random.nextInt(minCount, maxCount + 1)

        // Step 2: eligible pool = active minus anything sent to this receiver in the last 7 days.
        val eligible = active.filterNot { it.id in excludedIds }

        // Step 3/4: prefer the eligible (not-recently-sent) pool when it can cover Z on its own;
        // otherwise widen to the full active pool, which may reintroduce a recently-sent image
        // (allowed — the 7-day exclusion is a soft preference, not a hard constraint). Step 5
        // (fewer active images than Z) falls out of this naturally — take(z) below caps at
        // however many images the chosen pool actually has, never padding/duplicating to reach Z.
        val pool = if (eligible.size >= z) eligible else active
        val selected = pool.shuffled(random).take(z)

        return AppResult.Success(selected)
    }
}
