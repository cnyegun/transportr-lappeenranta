/*
 *    Trip Mapper - Converts Digitransit GraphQL responses to PTE Trip DTOs
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.JsonNode
import de.schildbach.pte.dto.*
import java.util.*

/**
 * Maps Digitransit itinerary data to PTE Trip objects.
 * 
 * ASSUMPTIONS:
 * - Digitransit uses milliseconds since epoch for timestamps
 * - PTE uses java.util.Date for times
 * - Mode names need to be converted from Digitransit to PTE format
 */
object TripMapper {

    /**
     * Maps a Digitransit itinerary to a PTE Trip.
     * 
     * @param itineraryNode The itinerary JSON node from GraphQL response
     * @param from Original from location (for reference)
     * @param to Original to location (for reference)
     * @return Fully constructed Trip object
     */
    fun mapToTrip(itineraryNode: JsonNode, from: Location, to: Location): Trip {
        val legs = mutableListOf<Trip.Leg>()
        val legsArray = itineraryNode.path("legs")
        
        if (legsArray.isArray) {
            for (i in 0 until legsArray.size()) {
                val legNode = legsArray[i]
                val leg = mapToLeg(legNode)
                if (leg != null) {
                    legs.add(leg)
                }
            }
        }

        // Extract trip metadata
        val duration = itineraryNode.path("duration").asLong(0) // seconds
        val walkDistance = itineraryNode.path("walkDistance").asLong(0).toInt()

        return Trip(
            id = null, // No unique ID provided by Digitransit
            from = from,
            to = to,
            legs = legs,
            duration = duration.toInt(),
            fare = null // Fare info not included in basic query
        )
    }

    /**
     * Maps a single leg of an itinerary to a PTE Trip.Leg.
     */
    private fun mapToLeg(legNode: JsonNode): Trip.Leg? {
        val mode = legNode.path("mode").asText()
        val fromNode = legNode.path("from")
        val toNode = legNode.path("to")
        
        val fromLocation = mapPlaceToLocation(fromNode)
        val toLocation = mapPlaceToLocation(toNode)
        
        if (fromLocation == null || toLocation == null) {
            return null
        }

        val departureTime = parseTimestamp(legNode.path("startTime").asLong())
        val arrivalTime = parseTimestamp(legNode.path("endTime").asLong())
        val isRealtime = legNode.path("realTime").asBoolean(false)

        return when (mapMode(mode)) {
            LegMode.WALK -> {
                val distance = legNode.path("distance").asInt(0)
                Trip.Individual(
                    Trip.Individual.Type.WALK,
                    fromLocation,
                    departureTime,
                    toLocation,
                    arrivalTime,
                    emptyList(), // No intermediate stops for walking
                    distance
                )
            }
            
            LegMode.BUS, LegMode.TRAM, LegMode.RAIL, LegMode.SUBWAY -> {
                val line = mapToLine(legNode.path("route"))
                val destination = legNode.path("trip").path("tripHeadsign").asText()
                    ?: legNode.path("headsign").asText()
                    ?: toLocation.name
                
                // Map intermediate stops
                val intermediateStops = mutableListOf<Stop>()
                val intermediatePlaces = legNode.path("intermediatePlaces")
                if (intermediatePlaces.isArray) {
                    for (i in 0 until intermediatePlaces.size()) {
                        val stop = mapToStop(intermediatePlaces[i])
                        if (stop != null) {
                            intermediateStops.add(stop)
                        }
                    }
                }

                Trip.Public(
                    line,
                    destination,
                    fromLocation,
                    departureTime,
                    null, // Departure position (platform)
                    toLocation,
                    arrivalTime,
                    null, // Arrival position
                    intermediateStops,
                    null, // Footway (not applicable)
                    isRealtime
                )
            }
            
            else -> null // Skip unknown modes
        }
    }

    /**
     * Maps a Digitransit Place node to a PTE Location.
     */
    private fun mapPlaceToLocation(placeNode: JsonNode): Location? {
        val name = placeNode.path("name").asText()
        val lat = (placeNode.path("lat").asDouble() * 1e6).toInt()
        val lon = (placeNode.path("lon").asDouble() * 1e6).toInt()
        
        // Check if this is a stop or just a coordinate
        val stopNode = placeNode.path("stop")
        val id = if (!stopNode.isMissingNode && !stopNode.isNull) {
            stopNode.path("gtfsId").asText()
        } else {
            null
        }

        val locationType = if (id != null) LocationType.STATION else LocationType.COORD

        return Location(
            locationType,
            id,
            lat,
            lon,
            if (name.isNotEmpty()) name else null,
            null // Place name (not used)
        )
    }

    /**
     * Maps a Digitransit intermediate place to a PTE Stop.
     */
    private fun mapToStop(placeNode: JsonNode): Stop? {
        val location = mapPlaceToLocation(placeNode) ?: return null
        val arrivalTime = parseTimestamp(placeNode.path("arrivalTime").asLong())
        
        return Stop(
            location,
            arrivalTime,
            null, // Departure time (same as arrival for intermediate)
            null, // Departure position
            null  // Arrival position
        )
    }

    /**
     * Maps a Digitransit route to a PTE Line.
     */
    private fun mapToLine(routeNode: JsonNode): Line {
        val id = routeNode.path("gtfsId").asText()
        val shortName = routeNode.path("shortName").asText()
        val longName = routeNode.path("longName").asText()
        val mode = routeNode.path("mode").asText()
        val color = parseColor(routeNode.path("color").asText())
        val textColor = parseColor(routeNode.path("textColor").asText())

        return Line(
            id = id,
            network = null, // Network info not provided
            product = mapProduct(mode),
            label = shortName.ifEmpty { longName },
            name = longName.ifEmpty { shortName },
            style = null, // Style will be applied by provider
            attr = null, // Accessibility attributes
            message = null // Service alerts
        ).apply {
            // Set colors if available
            if (color != null) {
                this.color = color
            }
            if (textColor != null) {
                this.textColor = textColor
            }
        }
    }

    /**
     * Maps Digitransit mode string to internal LegMode.
     */
    private fun mapMode(mode: String): LegMode {
        return when (mode.uppercase()) {
            "WALK" -> LegMode.WALK
            "BUS" -> LegMode.BUS
            "TRAM" -> LegMode.TRAM
            "RAIL", "TRAIN" -> LegMode.RAIL
            "SUBWAY" -> LegMode.SUBWAY
            "FERRY" -> LegMode.FERRY
            else -> LegMode.UNKNOWN
        }
    }

    /**
     * Maps Digitransit mode to PTE Product enum.
     */
    private fun mapProduct(mode: String): Product {
        return when (mode.uppercase()) {
            "BUS" -> Product.BUS
            "TRAM" -> Product.TRAM
            "RAIL", "TRAIN" -> Product.REGIONAL_TRAIN
            "SUBWAY" -> Product.SUBWAY
            "FERRY" -> Product.FERRY
            else -> Product.BUS // Default to bus
        }
    }

    /**
     * Parses a timestamp from milliseconds to Date.
     */
    private fun parseTimestamp(timestampMillis: Long): Date {
        return Date(timestampMillis)
    }

    /**
     * Parses hex color string to integer color value.
     * Digitransit returns colors like "FF0000", we need 0xFFFF0000 (ARGB)
     */
    private fun parseColor(colorStr: String?): Int? {
        if (colorStr.isNullOrEmpty()) return null
        
        return try {
            // Parse hex color and add alpha channel (fully opaque)
            (0xFF000000 + colorStr.toLong(16)).toInt()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private enum class LegMode {
        WALK, BUS, TRAM, RAIL, SUBWAY, FERRY, UNKNOWN
    }
}