package com.ris.imagedistributor.ui.receivers

/** Mirrors AppRouter.kt's AppRoute pattern — simple sealed-class routing, no navigation library. */
sealed class ReceiversRoute {
    data object List : ReceiversRoute()

    /** receiverId == null means add-new. */
    data class Edit(val receiverId: Long?) : ReceiversRoute()
}
