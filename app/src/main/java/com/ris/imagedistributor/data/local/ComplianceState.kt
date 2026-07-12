package com.ris.imagedistributor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row (id always 1). isCompliant/lastCheckedAt are a DISPLAY-ONLY cache for the UI —
 * ComplianceGate never reads them to decide whether to gate; it always re-checks live. [AD-11]
 */
@Entity(tableName = "compliance_state")
data class ComplianceState(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val nickname: String,
    val city: String,
    val locked: Boolean,
    val isCompliant: Boolean = true,
    val lastCheckedAt: Long? = null,
) {
    companion object {
        const val SINGLETON_ID: Long = 1L
    }
}
