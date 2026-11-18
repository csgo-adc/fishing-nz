package nz.fishingnz.app.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class OpenMeteoTest {
    private val forecast = "https://api.open-meteo.com/v1/forecast?latitude=-36.85&longitude=174.76&current=temperature_2m&timezone=auto"
    private val fallback = "https://historical-forecast-api.open-meteo.com/v1/forecast?latitude=-36.85&longitude=174.76&current=temperature_2m&timezone=auto"
    private val marine = "https://marine-api.open-meteo.com/v1/marine?latitude=-36.85&longitude=174.76&hourly=wave_height"

    @Before fun forgetFailures() = OpenMeteo.reset()
    @After fun cleanUp() = OpenMeteo.reset()

    @Test fun forecastRequestsHaveTheSecondServerAfterTheMainOne() {
        assertEquals(listOf(forecast, fallback), OpenMeteo.candidates(forecast))
    }

    @Test fun marineAndOtherRequestsHaveNoSecondServer() {
        assertEquals(listOf(marine), OpenMeteo.candidates(marine))
        val other = "https://api.open-meteo.com/v1/elevation?latitude=-36.85&longitude=174.76"
        assertEquals(listOf(other), OpenMeteo.candidates(other))
    }

    @Test fun aHangingMainServerFallsBackQuicklyAndTheSecondServerGoesFirstForHalfAnHour() {
        var clock = 1_000_000L
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val text = OpenMeteo.getText(forecast, 10_000, 15_000, now = { clock }) { url, connect, read ->
            calls += Triple(url, connect, read)
            if (url == forecast) throw SocketTimeoutException("connect timed out") else "{\"ok\":true}"
        }
        assertEquals("{\"ok\":true}", text)
        // The main server gets a shorter limit because another server follows; the second server keeps the caller's.
        assertEquals(listOf(Triple(forecast, 6_000, 10_000), Triple(fallback, 10_000, 15_000)), calls)

        clock += 29 * 60 * 1000L
        assertEquals(listOf(fallback, forecast), OpenMeteo.candidates(forecast, clock))
        calls.clear()
        OpenMeteo.getText(forecast, 15_000, 20_000, now = { clock }) { url, connect, read -> calls += Triple(url, connect, read); "{}" }
        // Going first, the second server still gets the full limits, since a request it has not answered lately is slow.
        assertEquals(listOf(Triple(fallback, 15_000, 20_000)), calls)
        clock += 2 * 60 * 1000L
        assertEquals(listOf(forecast, fallback), OpenMeteo.candidates(forecast, clock))
    }

    @Test fun aWorkingMainServerIsUsedAlone() {
        val calls = mutableListOf<String>()
        val text = OpenMeteo.getText(forecast, 8_000, 8_000) { url, _, _ -> calls += url; "{}" }
        assertEquals("{}", text)
        assertEquals(listOf(forecast), calls)
    }

    @Test fun theLastFailureIsThrownWhenNoServerAnswers() {
        val error = assertThrows(IOException::class.java) {
            OpenMeteo.getText(forecast, 8_000, 8_000) { url, _, _ -> throw IOException("Failed: $url") }
        }
        assertEquals("Failed: $fallback", error.message)
        assertEquals("Failed: $forecast", error.suppressed.single().message)
    }

    @Test fun marineFailuresAreNotRetried() {
        var count = 0
        assertThrows(IOException::class.java) {
            OpenMeteo.getText(marine, 8_000, 15_000) { _, connect, read ->
                count++; assertEquals(8_000, connect); assertEquals(15_000, read); throw IOException("down")
            }
        }
        assertEquals(1, count)
    }
}
