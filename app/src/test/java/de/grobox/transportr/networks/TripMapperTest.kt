/*
 *    TripMapper Tests
 *    Tests for converting Digitransit GraphQL responses to PTE Trip objects
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.schildbach.pte.dto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.*

class TripMapperTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `mapToTrip creates valid trip from complete itinerary`() {
        // Given: Complete itinerary JSON
        val itineraryJson = """
            {
                "duration": 1200,
                "walkDistance": 500,
                "legs": [
                    {
                        "mode": "WALK",
                        "startTime": 1704067200000,
                        "endTime": 1704067500000,
                        "from": {
                            "name": "Home",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Lappeenranta linja-autoasema",
                            "lat": 61.0590,
                            "lon": 28.1890,
                            "stop": {
                                "gtfsId": "HSL:1234",
                                "name": "Lappeenranta linja-autoasema"
                            }
                        },
                        "distance": 300
                    },
                    {
                        "mode": "BUS",
                        "startTime": 1704067500000,
                        "endTime": 1704068400000,
                        "realTime": true,
                        "from": {
                            "name": "Lappeenranta linja-autoasema",
                            "lat": 61.0590,
                            "lon": 28.1890,
                            "stop": {
                                "gtfsId": "HSL:1234",
                                "name": "Lappeenranta linja-autoasema"
                            }
                        },
                        "to": {
                            "name": "Skinnarila",
                            "lat": 61.0669,
                            "lon": 28.0961,
                            "stop": {
                                "gtfsId": "HSL:5678",
                                "name": "Skinnarila"
                            }
                        },
                        "route": {
                            "gtfsId": "HSL:1",
                            "shortName": "1",
                            "longName": "Linja-autoasema - Skinnarila",
                            "mode": "BUS",
                            "color": "00BFFF",
                            "textColor": "FFFFFF"
                        },
                        "trip": {
                            "gtfsId": "HSL:trip1",
                            "tripHeadsign": "Skinnarila"
                        },
                        "intermediatePlaces": [
                            {
                                "name": "Keskusta",
                                "lat": 61.0600,
                                "lon": 28.1000,
                                "arrivalTime": 1704067800000,
                                "stop": {
                                    "gtfsId": "HSL:901"
                                }
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.ADDRESS, null, 61058600, 28188700, "Home")
        val to = Location(LocationType.STATION, "HSL:5678", 61066900, 28096100, "Skinnarila")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Verify trip structure
        assertNotNull(trip)
        assertEquals(1200, trip.duration)
        assertEquals(2, trip.legs.size)
        
        // Verify first leg (walking)
        val firstLeg = trip.legs[0]
        assertTrue(firstLeg is Trip.Individual)
        assertEquals(Trip.Individual.Type.WALK, (firstLeg as Trip.Individual).type)
        assertEquals(300, firstLeg.distance)
        
        // Verify second leg (bus)
        val secondLeg = trip.legs[1]
        assertTrue(secondLeg is Trip.Public)
        val busLeg = secondLeg as Trip.Public
        assertNotNull(busLeg.line)
        assertEquals("1", busLeg.line.label)
        assertEquals("Skinnarila", busLeg.destination)
        assertTrue(busLeg.isRealTime)
        assertEquals(1, busLeg.intermediateStops.size)
    }

    @Test
    fun `mapToTrip handles walking-only trip`() {
        // Given: Walking-only itinerary
        val itineraryJson = """
            {
                "duration": 600,
                "walkDistance": 800,
                "legs": [
                    {
                        "mode": "WALK",
                        "startTime": 1704067200000,
                        "endTime": 1704067800000,
                        "from": {
                            "name": "Point A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Point B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "distance": 800
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.ADDRESS, null, 61058600, 28188700, "Point A")
        val to = Location(LocationType.ADDRESS, null, 61059000, 28189000, "Point B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Verify walking trip
        assertNotNull(trip)
        assertEquals(1, trip.legs.size)
        assertTrue(trip.legs[0] is Trip.Individual)
        assertEquals(800, (trip.legs[0] as Trip.Individual).distance)
    }

    @Test
    fun `mapToTrip handles missing optional fields gracefully`() {
        // Given: Itinerary with minimal data
        val itineraryJson = """
            {
                "duration": 900,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "BUS",
                        "startTime": 1704067200000,
                        "endTime": 1704068100000,
                        "from": {
                            "name": "Stop A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Stop B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "route": {
                            "gtfsId": "HSL:5",
                            "shortName": "5",
                            "longName": "",
                            "mode": "BUS"
                        },
                        "trip": {
                            "gtfsId": "HSL:trip5"
                        }
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "A", 61058600, 28188700, "Stop A")
        val to = Location(LocationType.STATION, "B", 61059000, 28189000, "Stop B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Should handle missing fields
        assertNotNull(trip)
        assertEquals(1, trip.legs.size)
        
        val leg = trip.legs[0] as Trip.Public
        assertNotNull(leg.line)
        assertEquals("5", leg.line.label)
        // Should use longName fallback for destination
        assertNotNull(leg.destination)
    }

    @Test
    fun `mapToTrip handles null trip headsign`() {
        // Given: Itinerary with null headsign
        val itineraryJson = """
            {
                "duration": 600,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "BUS",
                        "startTime": 1704067200000,
                        "endTime": 1704067800000,
                        "from": {
                            "name": "Stop A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Stop B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "route": {
                            "gtfsId": "HSL:2",
                            "shortName": "2",
                            "longName": "Route 2 Long Name",
                            "mode": "BUS"
                        },
                        "trip": {
                            "gtfsId": "HSL:trip2",
                            "tripHeadsign": null
                        }
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "A", 61058600, 28188700, "Stop A")
        val to = Location(LocationType.STATION, "B", 61059000, 28189000, "Stop B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Should use route longName as destination
        assertNotNull(trip)
        val leg = trip.legs[0] as Trip.Public
        assertEquals("Route 2 Long Name", leg.destination)
    }

    @Test
    fun `mapToTrip parses colors correctly`() {
        // Given: Itinerary with colored route
        val itineraryJson = """
            {
                "duration": 600,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "BUS",
                        "startTime": 1704067200000,
                        "endTime": 1704067800000,
                        "from": {
                            "name": "Stop A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Stop B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "route": {
                            "gtfsId": "HSL:3",
                            "shortName": "3",
                            "longName": "Route 3",
                            "mode": "BUS",
                            "color": "FF0000",
                            "textColor": "FFFFFF"
                        },
                        "trip": {
                            "gtfsId": "HSL:trip3",
                            "tripHeadsign": "Destination"
                        }
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "A", 61058600, 28188700, "Stop A")
        val to = Location(LocationType.STATION, "B", 61059000, 28189000, "Stop B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Verify colors are parsed (ARGB format)
        val leg = trip.legs[0] as Trip.Public
        assertNotNull(leg.line.color)
        assertNotNull(leg.line.textColor)
        // FF0000 with alpha = 0xFFFF0000
        assertEquals(0xFFFF0000.toInt(), leg.line.color)
        // FFFFFF with alpha = 0xFFFFFFFF
        assertEquals(0xFFFFFFFF.toInt(), leg.line.textColor)
    }

    @Test
    fun `mapToTrip handles multiple intermediate stops`() {
        // Given: Itinerary with multiple intermediate stops
        val itineraryJson = """
            {
                "duration": 1800,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "BUS",
                        "startTime": 1704067200000,
                        "endTime": 1704069000000,
                        "from": {
                            "name": "Start",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "End",
                            "lat": 61.0669,
                            "lon": 28.0961
                        },
                        "route": {
                            "gtfsId": "HSL:1",
                            "shortName": "1",
                            "longName": "Route 1",
                            "mode": "BUS"
                        },
                        "trip": {
                            "gtfsId": "HSL:trip1",
                            "tripHeadsign": "End"
                        },
                        "intermediatePlaces": [
                            {
                                "name": "Stop 1",
                                "lat": 61.0590,
                                "lon": 28.1000,
                                "arrivalTime": 1704067500000,
                                "stop": {
                                    "gtfsId": "HSL:s1"
                                }
                            },
                            {
                                "name": "Stop 2",
                                "lat": 61.0600,
                                "lon": 28.0900,
                                "arrivalTime": 1704067800000,
                                "stop": {
                                    "gtfsId": "HSL:s2"
                                }
                            },
                            {
                                "name": "Stop 3",
                                "lat": 61.0650,
                                "lon": 28.0800,
                                "arrivalTime": 1704068100000,
                                "stop": {
                                    "gtfsId": "HSL:s3"
                                }
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "start", 61058600, 28188700, "Start")
        val to = Location(LocationType.STATION, "end", 61066900, 28096100, "End")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Verify all intermediate stops are mapped
        val leg = trip.legs[0] as Trip.Public
        assertEquals(3, leg.intermediateStops.size)
        
        // Verify stop order and data
        assertEquals("Stop 1", leg.intermediateStops[0].location.name)
        assertEquals("Stop 2", leg.intermediateStops[1].location.name)
        assertEquals("Stop 3", leg.intermediateStops[2].location.name)
    }

    @Test
    fun `mapToTrip handles ferry mode`() {
        // Given: Itinerary with ferry
        val itineraryJson = """
            {
                "duration": 1200,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "FERRY",
                        "startTime": 1704067200000,
                        "endTime": 1704068400000,
                        "from": {
                            "name": "Harbor A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Harbor B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "route": {
                            "gtfsId": "HSL:F1",
                            "shortName": "F1",
                            "longName": "Ferry Route",
                            "mode": "FERRY"
                        },
                        "trip": {
                            "gtfsId": "HSL:ferry1",
                            "tripHeadsign": "Harbor B"
                        }
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "A", 61058600, 28188700, "Harbor A")
        val to = Location(LocationType.STATION, "B", 61059000, 28189000, "Harbor B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Verify ferry is mapped correctly
        val leg = trip.legs[0] as Trip.Public
        assertEquals(Product.FERRY, leg.line.product)
    }

    @Test
    fun `mapToTrip handles tram mode`() {
        // Given: Itinerary with tram
        val itineraryJson = """
            {
                "duration": 900,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "TRAM",
                        "startTime": 1704067200000,
                        "endTime": 1704068100000,
                        "from": {
                            "name": "Stop A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Stop B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "route": {
                            "gtfsId": "HSL:T1",
                            "shortName": "T1",
                            "longName": "Tram Route",
                            "mode": "TRAM"
                        },
                        "trip": {
                            "gtfsId": "HSL:tram1",
                            "tripHeadsign": "Stop B"
                        }
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "A", 61058600, 28188700, "Stop A")
        val to = Location(LocationType.STATION, "B", 61059000, 28189000, "Stop B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Verify tram is mapped correctly
        val leg = trip.legs[0] as Trip.Public
        assertEquals(Product.TRAM, leg.line.product)
    }

    @Test
    fun `mapToTrip handles unknown mode gracefully`() {
        // Given: Itinerary with unknown mode
        val itineraryJson = """
            {
                "duration": 600,
                "walkDistance": 0,
                "legs": [
                    {
                        "mode": "UNKNOWN_MODE",
                        "startTime": 1704067200000,
                        "endTime": 1704067800000,
                        "from": {
                            "name": "Stop A",
                            "lat": 61.0586,
                            "lon": 28.1887
                        },
                        "to": {
                            "name": "Stop B",
                            "lat": 61.0590,
                            "lon": 28.1890
                        },
                        "route": {
                            "gtfsId": "HSL:U1",
                            "shortName": "U1",
                            "longName": "Unknown Route",
                            "mode": "UNKNOWN_MODE"
                        },
                        "trip": {
                            "gtfsId": "HSL:unknown1",
                            "tripHeadsign": "Stop B"
                        }
                    }
                ]
            }
        """.trimIndent()

        val itineraryNode = objectMapper.readTree(itineraryJson)
        val from = Location(LocationType.STATION, "A", 61058600, 28188700, "Stop A")
        val to = Location(LocationType.STATION, "B", 61059000, 28189000, "Stop B")

        // When: Map to trip
        val trip = TripMapper.mapToTrip(itineraryNode, from, to)

        // Then: Should skip unknown mode (leg not added)
        assertNotNull(trip)
        assertEquals(0, trip.legs.size)
    }
}