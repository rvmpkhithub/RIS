package com.ris.imagedistributor.domain

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.repository.ImageRepository
import kotlin.random.Random

/** One due (receiver, scheduleTime) slot for a single dispatch tick — see [ImageSelectionEngine]. */
data class SelectionUnit(val receiver: Receiver, val scheduleTime: Int)

/**
 * CAP-3 selection algorithm, mechanics.md#Selection algorithm — [AD-10]
 *
 * Operator-requested redesign (2026-07-14, field-testing feedback): rather than each receiver
 * picking independently, [selectImagesForUnits] is called **once per dispatch tick** with every
 * due (receiver, scheduleTime) slot for that tick, and coordinates them so the combined total
 * matches exactly how many images are currently active — no active image sits unused, and no
 * image is ever sent to two different receivers in the same round.
 *
 * `random` is constructor-injected (defaults to Random.Default in production) specifically so
 * tests can substitute a deterministic source — every random draw in this class must go through
 * it, never a bare `Random.Default`/`(a..b).random()` call inline.
 */
class ImageSelectionEngine(
    private val imageRepository: ImageRepository,
    private val random: Random = Random.Default,
) {

    /**
     * Allocates active images to every unit in [units] for one dispatch tick:
     * 1. Draw each unit a uniform-random count within its own `[receiver.minCount, receiver.maxCount]`.
     * 2. Nudge the combined sum to match the active image count exactly — [Receiver.minCount] and
     *    [Receiver.maxCount] are hard bounds throughout this step; a unit's count is never pushed
     *    outside its own range.
     * 3. If the pool still can't cover every unit's minimum (sum of minimums itself exceeds the
     *    active count), scale everyone down toward — never below — 0. This is a defensive
     *    fallback only: `ReceiverEditScreen`'s own save-time validation refuses to let an
     *    operator create this situation in the first place, by blocking a receiver save whose
     *    minimum would push the combined total of all receivers' minimums past the active image
     *    count at save time.
     * 4. Shuffle the active pool once and slice it into each unit's final count — guarantees no
     *    image repeats across two different units in the same tick.
     *
     * Returns an empty list for every unit if there are no active images at all, and an empty map
     * if [units] itself is empty (nothing due this tick).
     */
    suspend fun selectImagesForUnits(units: List<SelectionUnit>): AppResult<Map<SelectionUnit, List<Image>>> {
        if (units.isEmpty()) return AppResult.Success(emptyMap())

        val active = when (val result = imageRepository.getActiveImages()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        if (active.isEmpty()) return AppResult.Success(units.associateWith { emptyList() })

        val counts = allocateCounts(units, target = active.size)
        val shuffled = active.shuffled(random)

        val result = mutableMapOf<SelectionUnit, List<Image>>()
        var offset = 0
        for (unit in units) {
            val count = counts.getValue(unit)
            result[unit] = shuffled.subList(offset, offset + count)
            offset += count
        }
        return AppResult.Success(result)
    }

    private fun allocateCounts(units: List<SelectionUnit>, target: Int): Map<SelectionUnit, Int> {
        val counts = units.associateWith { random.nextInt(it.receiver.minCount, it.receiver.maxCount + 1) }.toMutableMap()
        val sum = counts.values.sum()

        when {
            sum < target -> adjustToward(units, counts, amount = target - sum, delta = 1) { it.receiver.maxCount }
            sum > target -> {
                adjustToward(units, counts, amount = sum - target, delta = -1) { it.receiver.minCount }
                val stillOver = counts.values.sum() - target
                if (stillOver > 0) {
                    // Defensive fallback (see kdoc step 3) — sum of minimums > target.
                    adjustToward(units, counts, amount = stillOver, delta = -1) { 0 }
                }
            }
        }
        return counts
    }

    /**
     * Moves [amount] one image at a time from/to [counts], visiting units widest-range-first (so
     * a receiver with a bigger `max - min` spread absorbs more of the adjustment) each pass, until
     * [amount] is exhausted or every unit has hit [bound]. Never crosses [bound] for any unit.
     */
    private fun adjustToward(units: List<SelectionUnit>, counts: MutableMap<SelectionUnit, Int>, amount: Int, delta: Int, bound: (SelectionUnit) -> Int) {
        var remaining = amount
        val order = units.sortedByDescending { it.receiver.maxCount - it.receiver.minCount }
        while (remaining > 0) {
            var progressed = false
            for (unit in order) {
                if (remaining <= 0) break
                val current = counts.getValue(unit)
                val room = if (delta > 0) bound(unit) - current else current - bound(unit)
                if (room <= 0) continue
                counts[unit] = current + delta
                remaining--
                progressed = true
            }
            if (!progressed) break
        }
    }
}
