package com.ris.imagedistributor.ui.images

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagesRouteSaverTest {

    private val scope = SaverScope { true }

    @Test
    fun savesListRouteAsTheStringList() {
        val saved = with(scope) { with(ImagesRouteSaver) { save(ImagesRoute.List) } }
        assertEquals("list", saved)
    }

    @Test
    fun savesDetailRouteWithItsImageId() {
        val saved = with(scope) { with(ImagesRouteSaver) { save(ImagesRoute.Detail(42L)) } }
        assertEquals("detail:42", saved)
    }

    @Test
    fun restoresTheListStringBackToListRoute() {
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("list"))
    }

    @Test
    fun restoresADetailStringBackToDetailRouteWithTheSameId() {
        assertEquals(ImagesRoute.Detail(42L), ImagesRouteSaver.restore("detail:42"))
    }

    @Test
    fun restoresAMalformedStringToListInsteadOfCrashing() {
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("detail:not-a-number"))
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("garbage"))
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore(""))
    }
}
