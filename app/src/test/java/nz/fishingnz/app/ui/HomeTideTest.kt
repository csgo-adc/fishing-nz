package nz.fishingnz.app.ui

import nz.fishingnz.app.model.TidePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTideTest {
    // Low 0.4 m at 3 AM, high 2.6 m at 9 AM, low again at 3 PM.
    private val points = listOf(TidePoint("3:00 AM", 0.4, 180), TidePoint("9:00 AM", 2.6, 540), TidePoint("3:00 PM", 0.4, 900))

    @Test fun readsTheLevelOffTheCurve() {
        assertEquals(1.5, tideLevelAt(points, 360)!!, 1e-9)
        assertEquals(0.4, tideLevelAt(points, 60)!!, 1e-9)
        assertEquals(0.4, tideLevelAt(points, 1200)!!, 1e-9)
        assertNull(tideLevelAt(emptyList(), 600))
    }

    @Test fun saysWhetherTheTideIsComingIn() {
        assertEquals(true, tideRising(points, 360))
        assertEquals(false, tideRising(points, 700))
        assertNull(tideRising(points, 1430))
    }
}
