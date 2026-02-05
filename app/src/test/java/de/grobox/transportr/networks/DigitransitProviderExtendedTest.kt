/*
 *    Additional DigitransitProvider Tests
 *    More comprehensive test coverage
 */

package de.grobox.transportr.networks

import de.schildbach.pte.NetworkProvider
import de.schildbach.pte.dto.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.*

class DigitransitProviderExtendedTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var provider: DigitransitProvider

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        provider = DigitransitProvider(
            baseUrl = mockServer.url("/").toString(),
            apiKey = "test-api-key"
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // ============================================================================
    // CAPABILITY TESTS
    // ============================================================================

    @Test
    fun `provider supports all required capabilities`() {
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.TRIPS))
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.TRIPS_VIA))
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.DEPARTURES))
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.SUGGEST_LOCATIONS))
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.NEARBY_LOCATIONS))
    }

    @Test
    fun `provider does not support unsupported capabilities`() {
        assertFalse(provider.hasCapabilities(NetworkProvider.Capability.TRIP_ALT_IDS))
        assertFalse(provider.hasCapabilities(NetworkProvider.Capability.TRIP_RELOAD))
    }

    // ============================================================================
    // TRIP PLANNING EDGE CASES
    // ============================================================================

    @Test
    fun `queryTrips handles via location`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "plan": {
                            "itineraries": [
                                {
                                    "duration": 1800,
                                    "walkDistance": 200,
                                    "legs": [
                                        {
                                            "mode": "BUS",
                                            "startTime": 1704067200000,
                                            "endTime": 1704069000000,
                                            "from": {"name": "A", "lat": 61.0, "lon": 28.0},
                                            "to": {"name": "Via", "lat": 61.1, "lon": 28.1},
                                            "route": {"gtfsId": "R1", "shortName": "1", "longName": "Route", "mode": "BUS"},
                                            "trip": {"gtfsId": "T1", "tripHeadsign": "Via"}
                                        },
                                        {
                                            "mode": "BUS",
                                            "startTime": 1704069000000,
                                            "endTime": 1704070200000,
                                            "from": {"name": "Via", "lat": 61.1, "lon": 28.1},
                                            "to": {"name": "B", "lat": 61.2, "lon": 28.2},
                                            "route": {"gtfsId": "R2", "shortName": "2", "longName": "Route", "mode": "BUS"},
                                            "trip": {"gtfsId": "T2", "tripHeadsign": "B"}
                                        }
                                    ]
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        val from = Location(LocationType.STATION, "A", 61000000, 28000000, "A")
        val via = Location(LocationType.STATION, "Via", 61100000, 28100000, "Via")
        val to = Location(LocationType.STATION, "B", 61200000, 28200000, "B")
        
        val result = provider.queryTrips(from, via, to, Date(), true, null, null, null, null, null)

        assertEquals(QueryTripsResult.Status.OK, result.status)
        assertFalse(result.trips.isEmpty())
    }

    @Test
    fun `queryTrips handles arriveBy parameter`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""{"data":{"plan":{"itineraries":[]}}}""")
        
        mockServer.enqueue(mockResponse)

        val from = Location(LocationType.STATION, "A", 61000000, 28000000, "A")
        val to = Location(LocationType.STATION, "B", 61200000, 28200000, "B")
        
        // Test arriveBy = true (arrival time)
        provider.queryTrips(from, null, to, Date(), false, null, null, null, null, null)
        
        val request = mockServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("arriveBy: true"))
    }

    // ============================================================================
    // DEPARTURE EDGE CASES
    // ============================================================================

    @Test
    fun `queryDepartures handles late night times`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "stop": {
                            "name": "Test Stop",
                            "gtfsId": "HSL:1234",
                            "stoptimesWithoutPatterns": [
                                {
                                    "scheduledDeparture": 86340,
                                    "realtimeDeparture": 86340,
                                    "departureDelay": 0,
                                    "headsign": "Late Night Bus",
                                    "trip": {
                                        "route": {
                                            "gtfsId": "HSL:N1",
                                            "shortName": "N1",
                                            "longName": "Night Route",
                                            "mode": "BUS"
                                        }
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // 23:59:00 = 86340 seconds from midnight
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.JANUARY, 1, 0, 0, 0)
        
        val result = provider.queryDepartures("HSL:1234", calendar.time, 10, false)

        assertFalse(result.stationDepartures.isEmpty())
        val departure = result.stationDepartures[0].departures[0]
        assertEquals("Late Night Bus", departure.destination)
    }

    @Test
    fun `queryDepartures handles multiple routes at same stop`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "stop": {
                            "name": "Central Station",
                            "gtfsId": "HSL:CENTRAL",
                            "stoptimesWithoutPatterns": [
                                {
                                    "scheduledDeparture": 28800,
                                    "realtimeDeparture": 28800,
                                    "headsign": "Route 1",
                                    "trip": {
                                        "route": {"gtfsId": "HSL:1", "shortName": "1", "longName": "Line 1", "mode": "BUS"}
                                    }
                                },
                                {
                                    "scheduledDeparture": 28830,
                                    "realtimeDeparture": 28830,
                                    "headsign": "Route 2",
                                    "trip": {
                                        "route": {"gtfsId": "HSL:2", "shortName": "2", "longName": "Line 2", "mode": "BUS"}
                                    }
                                },
                                {
                                    "scheduledDeparture": 28900,
                                    "realtimeDeparture": 28900,
                                    "headsign": "Route 3",
                                    "trip": {
                                        "route": {"gtfsId": "HSL:3", "shortName": "3", "longName": "Line 3", "mode": "BUS"}
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        val result = provider.queryDepartures("HSL:CENTRAL", Date(), 10, false)

        assertEquals(1, result.stationDepartures.size)
        assertEquals(3, result.stationDepartures[0].departures.size)
        
        // Verify different routes
        val departures = result.stationDepartures[0].departures
        assertEquals("1", departures[0].line.label)
        assertEquals("2", departures[1].line.label)
        assertEquals("3", departures[2].line.label)
    }

    // ============================================================================
    // LOCATION SEARCH EDGE CASES
    // ============================================================================

    @Test
    fun `suggestLocations handles special characters in query`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "geocode": {
                            "hits": [
                                {
                                    "properties": {
                                        "name": "St. Mary's Hospital",
                                        "label": "St. Mary's Hospital, Lappeenranta",
                                        "layer": "venue"
                                    },
                                    "geometry": {
                                        "coordinates": [28.1887, 61.0586]
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        val result = provider.suggestLocations("St. Mary's", EnumSet.of(LocationType.ANY), 5)

        assertFalse(result.suggestedLocations.isEmpty())
        assertTrue(result.suggestedLocations[0].location.name!!.contains("St. Mary's"))
    }

    @Test
    fun `suggestLocations handles unicode characters`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "geocode": {
                            "hits": [
                                {
                                    "properties": {
                                        "name": "Keskustori",
                                        "label": "Keskustori, Lappeenranta",
                                        "layer": "stop"
                                    },
                                    "geometry": {
                                        "coordinates": [28.1887, 61.0586]
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        val result = provider.suggestLocations("ä", EnumSet.of(LocationType.ANY), 5)

        assertFalse(result.suggestedLocations.isEmpty())
    }

    // ============================================================================
    // NEARBY LOCATIONS EDGE CASES
    // ============================================================================

    @Test
    fun `queryNearbyLocations handles maxDistance limit`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "nearest": {
                            "edges": [
                                {
                                    "node": {
                                        "distance": 100,
                                        "place": {
                                            "gtfsId": "HSL:CLOSE",
                                            "name": "Close Stop",
                                            "lat": 61.0586,
                                            "lon": 28.1887
                                        }
                                    }
                                },
                                {
                                    "node": {
                                        "distance": 500,
                                        "place": {
                                            "gtfsId": "HSL:MEDIUM",
                                            "name": "Medium Stop",
                                            "lat": 61.0590,
                                            "lon": 28.1890
                                        }
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        val location = Location(LocationType.COORD, null, 61058600, 28188700, null)
        val result = provider.queryNearbyLocations(location, 300, 10)

        // Should include both stops (distances 100 and 500, but API filters by maxDistance)
        assertNotNull(result)
        assertFalse(result.locations.isEmpty())
    }

    @Test
    fun `queryNearbyLocations returns empty for remote location`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "nearest": {
                            "edges": []
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // Location far from any stops
        val location = Location(LocationType.COORD, null, 0, 0, null)
        val result = provider.queryNearbyLocations(location, 500, 10)

        assertTrue(result.locations.isEmpty())
    }

    // ============================================================================
    // ERROR HANDLING TESTS
    // ============================================================================

    @Test
    fun `handles HTTP 429 rate limit`() {
        val mockResponse = MockResponse().setResponseCode(429)
        mockServer.enqueue(mockResponse)

        val from = Location(LocationType.STATION, "A", 61000000, 28000000, "A")
        val to = Location(LocationType.STATION, "B", 61200000, 28200000, "B")
        
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null, null)

        assertEquals(QueryTripsResult.Status.SERVICE_DOWN, result.status)
    }

    @Test
    fun `handles HTTP 500 server error`() {
        val mockResponse = MockResponse().setResponseCode(500)
        mockServer.enqueue(mockResponse)

        val result = provider.queryDepartures("HSL:1234", Date(), 10, false)

        assertNotNull(result)
        assertTrue(result.stationDepartures.isEmpty())
    }

    @Test
    fun `handles empty response body`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("")
        
        mockServer.enqueue(mockResponse)

        val from = Location(LocationType.STATION, "A", 61000000, 28000000, "A")
        val to = Location(LocationType.STATION, "B", 61200000, 28200000, "B")
        
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null, null)

        assertEquals(QueryTripsResult.Status.SERVICE_DOWN, result.status)
    }

    @Test
    fun `handles JSON with missing data field`() {
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""{"errors": [{"message": "Invalid query"}]}""")
        
        mockServer.enqueue(mockResponse)

        val result = provider.suggestLocations("test", EnumSet.of(LocationType.ANY), 5)

        assertTrue(result.suggestedLocations.isEmpty())
    }

    @Test
    fun `handles network timeout gracefully`() {
        // Don't enqueue any response - simulate timeout
        // In real scenario, OkHttp would timeout, but MockWebServer will hang
        // This test documents expected behavior
        
        val from = Location(LocationType.STATION, "A", 61000000, 28000000, "A")
        val to = Location(LocationType.STATION, "B", 61200000, 28200000, "B")
        
        // With timeout configured in provider, this should return error instead of hanging
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null, null)

        // Should handle gracefully (timeout returns SERVICE_DOWN)
        assertNotNull(result)
    }

    // ============================================================================
    // AREA BOUNDING TEST
    // ============================================================================

    @Test
    fun `getArea returns Finland bounding box`() {
        val area = provider.getArea()
        
        assertEquals(2, area.size)
        // Southwest corner
        assertEquals(59.0, area[0].lat / 1e6, 0.001)
        assertEquals(19.0, area[0].lon / 1e6, 0.001)
        // Northeast corner
        assertEquals(70.0, area[1].lat / 1e6, 0.001)
        assertEquals(32.0, area[1].lon / 1e6, 0.001)
    }

    // ============================================================================
    // LINE STYLE TESTS
    // ============================================================================

    @Test
    fun `lineStyle uses provided colors`() {
        val line = Line(
            id = "test",
            network = null,
            product = Product.BUS,
            label = "1",
            name = "Test Line",
            style = null,
            attr = null,
            message = null
        ).apply {
            color = 0xFFFF0000.toInt() // Red
            textColor = 0xFFFFFFFF.toInt() // White
        }

        val style = provider.lineStyle(line)

        assertEquals(0xFFFF0000.toInt(), style.backgroundColor)
        assertEquals(0xFFFFFFFF.toInt(), style.foregroundColor)
    }

    @Test
    fun `lineStyle handles missing colors`() {
        val line = Line(
            id = "test",
            network = null,
            product = Product.BUS,
            label = "1",
            name = "Test Line",
            style = null,
            attr = null,
            message = null
        )
        // No colors set

        val style = provider.lineStyle(line)

        // Should use defaults (red background, white text)
        assertEquals(0xFFFF0000.toInt(), style.backgroundColor)
        assertEquals(0xFFFFFFFF.toInt(), style.foregroundColor)
    }
}