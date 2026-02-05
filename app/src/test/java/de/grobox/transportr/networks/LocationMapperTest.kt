/*
 *    LocationMapper Tests
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.ObjectMapper
import de.schildbach.pte.dto.LocationType
import org.junit.Assert.*
import org.junit.Test

class LocationMapperTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `mapGeocodingToLocations extracts all hits`() {
        val geocodeJson = """
            {
                "hits": [
                    {
                        "properties": {
                            "name": "Lappeenranta linja-autoasema",
                            "label": "Lappeenranta linja-autoasema, Lappeenranta",
                            "layer": "stop"
                        },
                        "geometry": {
                            "coordinates": [28.1887, 61.0586]
                        }
                    },
                    {
                        "properties": {
                            "name": "Keskusta",
                            "label": "Keskusta, Lappeenranta",
                            "layer": "address"
                        },
                        "geometry": {
                            "coordinates": [28.1900, 61.0600]
                        }
                    }
                ]
            }
        """.trimIndent()

        val geocodeNode = objectMapper.readTree(geocodeJson)
        val locations = LocationMapper.mapGeocodingToLocations(geocodeNode)

        assertEquals(2, locations.size)
        
        // First is a stop
        assertEquals(LocationType.STATION, locations[0].type)
        assertEquals("Lappeenranta linja-autoasema", locations[0].name)
        
        // Second is an address
        assertEquals(LocationType.ADDRESS, locations[1].type)
        assertEquals("Keskusta", locations[1].name)
    }

    @Test
    fun `mapNearbyToLocations includes distance info`() {
        val nearbyJson = """
            {
                "edges": [
                    {
                        "node": {
                            "distance": 150,
                            "place": {
                                "gtfsId": "HSL:1234",
                                "name": "Nearby Stop 1",
                                "lat": 61.0586,
                                "lon": 28.1887
                            }
                        }
                    },
                    {
                        "node": {
                            "distance": 350,
                            "place": {
                                "gtfsId": "HSL:5678",
                                "name": "Nearby Stop 2",
                                "lat": 61.0590,
                                "lon": 28.1890
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        val nearbyNode = objectMapper.readTree(nearbyJson)
        val locations = LocationMapper.mapNearbyToLocations(nearbyNode)

        assertEquals(2, locations.size)
        
        // Verify distance is included in name
        assertTrue(locations[0].name.contains("150m"))
        assertTrue(locations[1].name.contains("350m"))
    }

    @Test
    fun `mapGeocodingToLocations handles empty results`() {
        val geocodeJson = """
            {
                "hits": []
            }
        """.trimIndent()

        val geocodeNode = objectMapper.readTree(geocodeJson)
        val locations = LocationMapper.mapGeocodingToLocations(geocodeNode)

        assertTrue(locations.isEmpty())
    }
}