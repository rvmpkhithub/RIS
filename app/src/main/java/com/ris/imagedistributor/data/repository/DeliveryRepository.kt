package com.ris.imagedistributor.data.repository

import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.domain.AppResult

/** Dispatches a single image to a receiver via their configured channel. [AD-2, AD-5] */
interface DeliveryRepository {
    suspend fun send(receiver: Receiver, image: Image): AppResult<Unit>
}
