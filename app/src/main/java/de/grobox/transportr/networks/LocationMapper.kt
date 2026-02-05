/*
 *    Location Mapper - Converts Digitransit geocoding and nearby data to PTE Location DTOs
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.JsonNode
import de.schildbach.pte.dto.Location
import de.schildbach.pte.dto.LocationType

/**
 * Maps Digitransit geocoding and nearby queries to PTE Location objects.
 * 
 * ASSUMPTIONS:
 * - Geocoding returns coordinates as [lon, lat] array
 * - Nearby queries return stops with distance information
 * - Different place types need different handling
 */
object LocationMapper {

    /**
     * Maps geocoding results to list of locations.
     * 
     * @param geocodeNode The geocode node from GraphQL response
     * @return List of Location objects
     */
    fun mapGeocodingToLocations(geocodeNode: JsonNode): List<Location> {
        val locations = mutableListOf<Location>()
        
        val hits = geocodeNode.path("hits")
        if (!hits.isArray) {
            return locations
        }

        for (i in 0 until hits.size()) {
            val hit = hits[i]
            val location = mapGeocodingHitToLocation(hit)
            if (location != null) {
                locations.add(location)
            }
        }

        return locations
    }

    /**
     * Maps a single geocoding hit to a Location.
     */
    private fun mapGeocodingHitToLocation(hitNode: JsonNode): Location? {
        val properties = hitNode.path("properties")
        val geometry = hitNode.path("geometry")

        val name = properties.path("name").asText()
        val label = properties.path("label").asText()
        val layer = properties.path("layer").asText()

        // Coordinates are [lon, lat] in GeoJSON format
        val coords = geometry.path("coordinates")
        if (!coords.isArray || coords.size() < 2) {
            return null
        }

        val lon = (coords[0].asDouble() * 1e6).toInt()
        val lat = (coords[1].asDouble() * 1e6).toInt()

        // Determine location type based on layer
        val locationType = when (layer) {
            "stop" -> LocationType.STATION
            "address" -> LocationType.ADDRESS
            "venue", "place" -> LocationType.POI
            else -> LocationType.ANY
        }

        return Location(
            locationType,
            null, // No ID for geocoded locations
            lat,
            lon,
            if (name.isNotEmpty()) name else null,
            if (label.isNotEmpty() && label != name) label else null
        )
    }

    /**
     * Maps nearby query results to list of locations.
     * 
     * @param nearestNode The nearest node from GraphQL response
     * @return List of Location objects with distance info
     */
    fun mapNearbyToLocations(nearestNode: JsonNode): List<Location> {
        val locations = mutableListOf<Location>()
        
        val edges = nearestNode.path("edges")
        if (!edges.isArray) {
            return locations
        }

        for (i in 0 until edges.size()) {
            val edge = edges[i]
            val node = edge.path("node")
            val location = mapNearbyNodeToLocation(node)
            if (location != null) {
                locations.add(location)
            }
        }

        return locations
    }

    /**
     * Maps a single nearby node to a Location.
     */
    private fun mapNearbyNodeToLocation(node: JsonNode): Location? {
        val distance = node.path("distance").asInt()
        val place = node.path("place")

        val id = place.path("gtfsId").asText()
        val name = place.path("name").asText()
        val lat = (place.path("lat").asDouble() * 1e6).toInt()
        val lon = (place.path("lon").asDouble() * 1e6).toInt()

        if (name.isEmpty()) {
            return null
        }

        // For nearby stops, we include the distance in the name for display
        // This is a simple approach - in production you might want a separate data structure
        val displayName = "$name (${distance}m)"

        return Location(
            LocationType.STATION,
            id.ifEmpty { null },
            lat,
            lon,
            displayName,
            null
        )
    }
}