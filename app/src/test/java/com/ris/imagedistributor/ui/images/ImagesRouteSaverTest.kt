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
    fun savesDetailRouteWithItsImageIdAndRequireTitleFlag() {
        val saved = with(scope) { with(ImagesRouteSaver) { save(ImagesRoute.Detail(42L)) } }
        assertEquals("detail:42:false", saved)
    }

    @Test
    fun savesDetailRouteWithRequireTitleOnSaveTrue() {
        val saved = with(scope) { with(ImagesRouteSaver) { save(ImagesRoute.Detail(42L, requireTitleOnSave = true)) } }
        assertEquals("detail:42:true", saved)
    }

    @Test
    fun restoresTheListStringBackToListRoute() {
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("list"))
    }

    @Test
    fun restoresADetailStringBackToDetailRouteWithTheSameId() {
        assertEquals(ImagesRoute.Detail(42L), ImagesRouteSaver.restore("detail:42:false"))
    }

    @Test
    fun restoresADetailStringWithRequireTitleOnSaveTrue() {
        assertEquals(ImagesRoute.Detail(42L, requireTitleOnSave = true), ImagesRouteSaver.restore("detail:42:true"))
    }

    @Test
    fun restoresAMalformedStringToListInsteadOfCrashing() {
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("detail:not-a-number:false"))
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("garbage"))
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore(""))
        // Pre-this-change format (no requireTitleOnSave segment) — safely bounces to List rather
        // than crashing, same as any other foreign/malformed string.
        assertEquals(ImagesRoute.List, ImagesRouteSaver.restore("detail:42"))
    }
}
