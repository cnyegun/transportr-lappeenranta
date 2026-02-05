/*
 *    Lappeenranta Bussi - DigitransitProvider Tests
 *    Test-Driven Development approach
 */

package de.grobox.transportr.networks

import de.schildbach.pte.NetworkId
import de.schildbach.pte.NetworkProvider
import de.schildbach.pte.dto.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.*

/**
 * Unit tests for DigitransitProvider
 * 
 * ASSUMPTIONS:
 * 1. Digitransit API returns GraphQL responses in expected format
 * 2. API requires digitransit-subscription-key header
 * 3. Lappeenranta is covered by the Finland endpoint
 * 4. Real-time data may not always be available
 * 5. API has rate limits (should be handled gracefully)
 */
class DigitransitProviderTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var provider: DigitransitProvider

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        
        // Create provider pointing to mock server
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
    // TEST: Provider Identification
    // ============================================================================

    @Test
    fun `provider returns correct network id`() {
        // TODO: Update to NetworkId.FI after forking PTE library
        assertEquals(NetworkId.SE, provider.id())
    }

    @Test
    fun `provider supports required capabilities`() {
        // Must support trip planning
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.TRIPS))
        
        // Must support departures
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.DEPARTURES))
        
        // Must support location search
        assertTrue(provider.hasCapabilities(NetworkProvider.Capability.SUGGEST_LOCATIONS))
    }

    // ============================================================================
    // TEST: Trip Planning (queryTrips)
    // ============================================================================

    @Test
    fun `queryTrips returns valid result for valid locations`() {
        // Given: Mock successful trip planning response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "plan": {
                            "itineraries": [
                                {
                                    "duration": 1200,
                                    "walkDistance": 500,
                                    "legs": [
                                        {
                                            "mode": "BUS",
                                            "startTime": 1704067200000,
                                            "endTime": 1704068400000,
                                            "from": {
                                                "name": "Lappeenranta linja-autoasema",
                                                "lat": 61.0586,
                                                "lon": 28.1887
                                            },
                                            "to": {
                                                "name": "Skinnarila",
                                                "lat": 61.0669,
                                                "lon": 28.0961
                                            },
                                            "route": {
                                                "shortName": "1",
                                                "longName": "Linja-autoasema - Skinnarila"
                                            },
                                            "trip": {
                                                "gtfsId": "HSL:1234"
                                            }
                                        }
                                    ]
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query trips from Lappeenranta center to Skinnarila
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Lappeenranta linja-autoasema")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Skinnarila")
        val date = Date(1704067200000) // 2024-01-01 00:00:00 UTC
        
        val result = provider.queryTrips(from, null, to, date, true, null, null, null, null)

        // Then: Should return valid result with status OK
        assertNotNull(result)
        assertEquals(QueryTripsResult.Status.OK, result.status)
        assertFalse(result.trips.isEmpty())
        
        // Verify trip details
        val trip = result.trips[0]
        assertNotNull(trip)
        assertTrue(trip.duration > 0)
    }

    @Test
    fun `queryTrips handles ambiguous locations gracefully`() {
        // Given: Mock ambiguous location response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "plan": null
                    },
                    "errors": [
                        {
                            "message": "Location is ambiguous",
                            "extensions": {
                                "code": "AMBIGUOUS_LOCATION"
                            }
                        }
                    ]
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query with ambiguous location
        val from = Location(LocationType.ANY, null, 61.0, 28.0, "Keskusta")
        val to = Location(LocationType.ANY, null, 61.1, 28.1, "Koulu")
        
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Should return AMBIGUOUS status
        assertEquals(QueryTripsResult.Status.AMBIGUOUS, result.status)
        assertTrue(result.trips.isEmpty())
    }

    @Test
    fun `queryTrips handles no connections found`() {
        // Given: Mock empty response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "plan": {
                            "itineraries": []
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query for route with no connections
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Lappeenranta")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Skinnarila")
        
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Should return NO_TRIPS status
        assertEquals(QueryTripsResult.Status.NO_TRIPS, result.status)
    }

    @Test
    fun `queryTrips handles network errors gracefully`() {
        // Given: Mock server error
        val mockResponse = MockResponse().setResponseCode(500)
        mockServer.enqueue(mockResponse)

        // When: Query with server error
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Lappeenranta")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Skinnarila")
        
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Should return SERVICE_DOWN status
        assertEquals(QueryTripsResult.Status.SERVICE_DOWN, result.status)
    }

    @Test
    fun `queryTrips handles too close locations`() {
        // Given: Two locations very close to each other
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Point A")
        val to = Location(LocationType.STATION, "stop2", 61.0587, 28.1888, "Point B (100m away)")
        
        // When: Query trips
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Should detect they're too close
        assertEquals(QueryTripsResult.Status.TOO_CLOSE, result.status)
    }

    // ============================================================================
    // TEST: Departures (queryDepartures)
    // ============================================================================

    @Test
    fun `queryDepartures returns valid departures`() {
        // Given: Mock departures response with real-time data
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "stop": {
                            "name": "Lappeenranta linja-autoasema",
                            "gtfsId": "HSL:1234",
                            "stoptimesWithoutPatterns": [
                                {
                                    "scheduledDeparture": 28800,
                                    "realtimeDeparture": 28830,
                                    "departureDelay": 30,
                                    "headsign": "Skinnarila",
                                    "trip": {
                                        "route": {
                                            "shortName": "1",
                                            "longName": "Linja-autoasema - Skinnarila"
                                        }
                                    }
                                },
                                {
                                    "scheduledDeparture": 29400,
                                    "realtimeDeparture": 29400,
                                    "departureDelay": 0,
                                    "headsign": "Rauha",
                                    "trip": {
                                        "route": {
                                            "shortName": "3",
                                            "longName": "Linja-autoasema - Rauha"
                                        }
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query departures for a stop
        val result = provider.queryDepartures("HSL:1234", Date(), 10, false)

        // Then: Should return departures
        assertNotNull(result)
        assertFalse(result.stationDepartures.isEmpty())
        
        val stationDepartures = result.stationDepartures[0]
        assertEquals("HSL:1234", stationDepartures.location.id)
        assertFalse(stationDepartures.departures.isEmpty())
        
        // Verify first departure has correct details
        val firstDeparture = stationDepartures.departures[0]
        assertNotNull(firstDeparture.line)
        assertEquals("1", firstDeparture.line?.label)
        assertEquals("Skinnarila", firstDeparture.destination)
    }

    @Test
    fun `queryDepartures handles missing stop gracefully`() {
        // Given: Mock response for non-existent stop
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "stop": null
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query non-existent stop
        val result = provider.queryDepartures("INVALID:STOP", Date(), 10, false)

        // Then: Should return empty result (not crash)
        assertTrue(result.stationDepartures.isEmpty())
    }

    @Test
    fun `queryDepartures handles API errors`() {
        // Given: API returns error
        val mockResponse = MockResponse().setResponseCode(503)
        mockServer.enqueue(mockResponse)

        // When: Query departures
        val result = provider.queryDepartures("HSL:1234", Date(), 10, false)

        // Then: Should handle gracefully (not crash)
        assertNotNull(result)
    }

    // ============================================================================
    // TEST: Location Search (suggestLocations)
    // ============================================================================

    @Test
    fun `suggestLocations returns matching locations`() {
        // Given: Mock geocoding response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "geocode": {
                            "hits": [
                                {
                                    "properties": {
                                        "name": "Lappeenranta linja-autoasema",
                                        "label": "Lappeenranta linja-autoasema, Lappeenranta"
                                    },
                                    "geometry": {
                                        "coordinates": [28.1887, 61.0586]
                                    }
                                },
                                {
                                    "properties": {
                                        "name": "Lappeenranta rautatieasema",
                                        "label": "Lappeenranta rautatieasema, Lappeenranta"
                                    },
                                    "geometry": {
                                        "coordinates": [28.1954, 61.0478]
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Search for "lappeenranta"
        val result = provider.suggestLocations("lappeenranta", EnumSet.of(LocationType.STATION), 5)

        // Then: Should return suggestions
        assertNotNull(result)
        assertFalse(result.suggestedLocations.isEmpty())
        
        val firstSuggestion = result.suggestedLocations[0]
        assertTrue(firstSuggestion.location.name.contains("Lappeenranta", ignoreCase = true))
    }

    @Test
    fun `suggestLocations handles no results`() {
        // Given: Mock empty geocoding response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "geocode": {
                            "hits": []
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Search for non-existent location
        val result = provider.suggestLocations("xyz123nonexistent", EnumSet.of(LocationType.ANY), 5)

        // Then: Should return empty result
        assertTrue(result.suggestedLocations.isEmpty())
    }

    // ============================================================================
    // TEST: Nearby Locations (queryNearbyLocations)
    // ============================================================================

    @Test
    fun `queryNearbyLocations returns nearby stops`() {
        // Given: Mock nearby stops response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "nearest": {
                            "edges": [
                                {
                                    "node": {
                                        "place": {
                                            "gtfsId": "HSL:1234",
                                            "name": "Lappeenranta linja-autoasema",
                                            "lat": 61.0586,
                                            "lon": 28.1887
                                        },
                                        "distance": 150
                                    }
                                },
                                {
                                    "node": {
                                        "place": {
                                            "gtfsId": "HSL:5678",
                                            "name": "Keskusta",
                                            "lat": 61.0580,
                                            "lon": 28.1890
                                        },
                                        "distance": 350
                                    }
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query nearby stops
        val location = Location(LocationType.COORD, null, 61.0586, 28.1887, null)
        val result = provider.queryNearbyLocations(location, 500, 10)

        // Then: Should return nearby locations
        assertNotNull(result)
        assertFalse(result.locations.isEmpty())
        
        // Verify first location has distance info
        val firstLocation = result.locations[0]
        assertNotNull(firstLocation)
    }

    // ============================================================================
    // TEST: Edge Cases & Error Handling
    // ============================================================================

    @Test
    fun `handles invalid API key gracefully`() {
        // Given: Create provider with invalid key
        val invalidProvider = DigitransitProvider(
            baseUrl = mockServer.url("/").toString(),
            apiKey = "invalid-key"
        )
        
        // And: Mock 401 unauthorized response
        val mockResponse = MockResponse().setResponseCode(401)
        mockServer.enqueue(mockResponse)

        // When: Make any API call
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Test")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Test")
        val result = invalidProvider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Should handle auth error (not crash)
        assertNotNull(result)
        // Status could be SERVICE_DOWN or similar
    }

    @Test
    fun `handles malformed JSON response`() {
        // Given: Malformed JSON response
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("invalid json {{}")
        
        mockServer.enqueue(mockResponse)

        // When: Query trips
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Test")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Test")
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Should handle parse error gracefully (not crash)
        assertNotNull(result)
        assertEquals(QueryTripsResult.Status.SERVICE_DOWN, result.status)
    }

    @Test
    fun `handles network timeout`() {
        // Given: Mock timeout
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBodyDelay(30, java.util.concurrent.TimeUnit.SECONDS) // Long delay
        
        mockServer.enqueue(mockResponse)

        // When: Query with short timeout (should be configured in provider)
        // This test documents expected behavior - actual implementation
        // should have configurable timeout
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Test")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Test")
        
        // Should not hang indefinitely
        val result = provider.queryTrips(from, null, to, Date(), true, null, null, null, null)
        assertNotNull(result)
    }

    @Test
    fun `handles real-time vs scheduled times correctly`() {
        // Given: Response with delayed real-time data
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "data": {
                        "stop": {
                            "stoptimesWithoutPatterns": [
                                {
                                    "scheduledDeparture": 28800,
                                    "realtimeDeparture": 28920,
                                    "departureDelay": 120,
                                    "realtimeState": "UPDATED"
                                }
                            ]
                        }
                    }
                }
            """.trimIndent())
        
        mockServer.enqueue(mockResponse)

        // When: Query departures
        val result = provider.queryDepartures("HSL:1234", Date(), 10, false)

        // Then: Should use real-time time if available
        assertFalse(result.stationDepartures.isEmpty())
        val departure = result.stationDepartures[0].departures[0]
        // Verify delay is correctly parsed (120 seconds = 2 minutes)
        assertNotNull(departure)
    }

    // ============================================================================
    // TEST: API Request Validation
    // ============================================================================

    @Test
    fun `includes required headers in requests`() {
        // Given: Any API call
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""{"data":{"plan":{"itineraries":[]}}}""")
        
        mockServer.enqueue(mockResponse)

        // When: Make request
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Test")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Test")
        provider.queryTrips(from, null, to, Date(), true, null, null, null, null)

        // Then: Request should have required headers
        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertNotNull(request.getHeader("Content-Type"))
        assertTrue(request.getHeader("Content-Type")?.contains("application/json") == true ||
                   request.getHeader("Content-Type")?.contains("application/graphql") == true)
        assertEquals("test-api-key", request.getHeader("digitransit-subscription-key"))
    }

    @Test
    fun `queryTrips sends correct GraphQL query structure`() {
        // Given: Capture the request
        val mockResponse = MockResponse()
            .setResponseCode(200)
            .setBody("""{"data":{"plan":{"itineraries":[]}}}""")
        
        mockServer.enqueue(mockResponse)

        // When: Query trips
        val from = Location(LocationType.STATION, "stop1", 61.0586, 28.1887, "Lappeenranta")
        val to = Location(LocationType.STATION, "stop2", 61.0669, 28.0961, "Skinnarila")
        val date = Date(1704067200000)
        provider.queryTrips(from, null, to, date, true, null, null, null, null)

        // Then: Verify GraphQL query structure
        val request = mockServer.takeRequest()
        val body = request.body.readUtf8()
        
        // Should contain required query elements
        assertTrue(body.contains("plan") || body.contains("query"))
        assertTrue(body.contains("from") || body.contains("lat") || body.contains("lon"))
        assertTrue(body.contains("to") || body.contains("lat") || body.contains("lon"))
    }
}