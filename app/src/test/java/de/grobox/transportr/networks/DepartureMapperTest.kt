/*
 *    DepartureMapper Tests
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.ObjectMapper
import de.schildbach.pte.dto.DateFormatter
import org.junit.Assert.*
import org.junit.Test
import java.util.*

class DepartureMapperTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `mapToDepartures handles realtime and scheduled times`() {
        val stopJson = """
            {
                "name": "Test Stop",
                "gtfsId": "HSL:1234",
                "stoptimesWithoutPatterns": [
                    {
                        "scheduledDeparture": 28800,
                        "realtimeDeparture": 28830,
                        "departureDelay": 30,
                        "headsign": "Destination A",
                        "trip": {
                            "route": {
                                "gtfsId": "HSL:1",
                                "shortName": "1",
                                "longName": "Route 1",
                                "mode": "BUS"
                            }
                        }
                    },
                    {
                        "scheduledDeparture": 29400,
                        "realtimeDeparture": 29400,
                        "departureDelay": 0,
                        "headsign": "Destination B",
                        "trip": {
                            "route": {
                                "gtfsId": "HSL:2",
                                "shortName": "2",
                                "longName": "Route 2",
                                "mode": "BUS"
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        val stopNode = objectMapper.readTree(stopJson)
        val departures = DepartureMapper.mapToDepartures(stopNode, "HSL:1234")

        assertEquals(2, departures.size)
        
        // First departure is delayed
        val firstDep = departures[0]
        assertNotNull(firstDep.predictedTime)
        assertTrue(firstDep.predictedTime!!.after(firstDep.plannedTime))
        assertEquals("Destination A", firstDep.destination)
        
        // Second departure is on time
        val secondDep = departures[1]
        assertNotNull(secondDep.predictedTime)
        assertEquals(secondDep.plannedTime, secondDep.predictedTime)
        assertEquals("Destination B", secondDep.destination)
    }

    @Test
    fun `mapToDepartures handles canceled trips`() {
        val stopJson = """
            {
                "name": "Test Stop",
                "gtfsId": "HSL:1234",
                "stoptimesWithoutPatterns": [
                    {
                        "scheduledDeparture": 28800,
                        "realtimeDeparture": 28800,
                        "departureDelay": 0,
                        "realtimeState": "CANCELED",
                        "headsign": "Canceled Destination",
                        "trip": {
                            "route": {
                                "gtfsId": "HSL:3",
                                "shortName": "3",
                                "longName": "Route 3",
                                "mode": "BUS"
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        val stopNode = objectMapper.readTree(stopJson)
        val departures = DepartureMapper.mapToDepartures(stopNode, "HSL:1234")

        assertEquals(1, departures.size)
        assertTrue(departures[0].isCancelled)
    }

    @Test
    fun `mapToDepartures handles missing stop gracefully`() {
        val stopJson = """
            {
                "stop": null
            }
        """.trimIndent()

        val stopNode = objectMapper.readTree(stopJson)
        val departures = DepartureMapper.mapToDepartures(stopNode, "HSL:1234")

        assertTrue(departures.isEmpty())
    }

    @Test
    fun `mapToDepartures handles empty stoptimes`() {
        val stopJson = """
            {
                "name": "Test Stop",
                "gtfsId": "HSL:1234",
                "stoptimesWithoutPatterns": []
            }
        """.trimIndent()

        val stopNode = objectMapper.readTree(stopJson)
        val departures = DepartureMapper.mapToDepartures(stopNode, "HSL:1234")

        assertTrue(departures.isEmpty())
    }
}