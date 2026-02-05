/*
 *    Departure Mapper - Converts Digitransit stop data to PTE Departure DTOs
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.JsonNode
import de.schildbach.pte.dto.DateFormatter
import de.schildbach.pte.dto.Departure
import de.schildbach.pte.dto.Line
import de.schildbach.pte.dto.Location
import de.schildbach.pte.dto.Product
import java.util.*

/**
 * Maps Digitransit stop time data to PTE Departure objects.
 * 
 * ASSUMPTIONS:
 * - Digitransit returns times in seconds since midnight
 * - Real-time data includes delay information
 * - Some fields may be null for canceled trips
 */
object DepartureMapper {

    /**
     * Maps stop node to list of departures.
     * 
     * @param stopNode The stop JSON node containing stoptimesWithoutPatterns
     * @param stationId The station/stop ID for reference
     * @return List of Departure objects
     */
    fun mapToDepartures(stopNode: JsonNode, stationId: String): List<Departure> {
        val departures = mutableListOf<Departure>()
        
        val stoptimes = stopNode.path("stoptimesWithoutPatterns")
        if (!stoptimes.isArray) {
            return departures
        }

        // Get current date for building departure times
        val today = Calendar.getInstance()
        val baseDate = DateFormatter.toDate(today, 0)

        for (i in 0 until stoptimes.size()) {
            val stoptimeNode = stoptimes[i]
            val departure = mapToDeparture(stoptimeNode, baseDate)
            if (departure != null) {
                departures.add(departure)
            }
        }

        return departures
    }

    /**
     * Maps a single stoptime node to a Departure.
     */
    private fun mapToDeparture(stoptimeNode: JsonNode, baseDate: Date): Departure? {
        try {
            // Parse scheduled and real-time times
            val scheduledSeconds = stoptimeNode.path("scheduledDeparture").asInt()
            val realtimeSeconds = stoptimeNode.path("realtimeDeparture").asInt()
            val delaySeconds = stoptimeNode.path("departureDelay").asInt(0)
            
            // Determine which time to use (prefer real-time if available)
            val useRealtime = stoptimeNode.has("realtimeDeparture") && 
                             !stoptimeNode.path("realtimeDeparture").isNull
            
            val departureSeconds = if (useRealtime) realtimeSeconds else scheduledSeconds
            
            // Convert seconds since midnight to Date
            val plannedTime = addSecondsToDate(baseDate, scheduledSeconds)
            val predictedTime = if (useRealtime) {
                addSecondsToDate(baseDate, realtimeSeconds)
            } else {
                null
            }

            // Map the route/line
            val routeNode = stoptimeNode.path("trip").path("route")
            val line = mapToLine(routeNode)

            // Get destination
            val destination = stoptimeNode.path("headsign").asText()
                .ifEmpty { line.name }
                .ifEmpty { "Unknown" }

            // Check if this is a canceled trip
            val isCanceled = stoptimeNode.path("realtimeState").asText() == "CANCELED"

            return Departure(
                plannedTime,
                predictedTime,
                line,
                0, // Position (platform) - not always available
                destination,
                null, // No specific destination location object
                isCanceled
            )

        } catch (e: Exception) {
            // Return null if we can't parse this departure
            return null
        }
    }

    /**
     * Maps a Digitransit route to a PTE Line.
     */
    private fun mapToLine(routeNode: JsonNode): Line {
        val id = routeNode.path("gtfsId").asText()
        val shortName = routeNode.path("shortName").asText()
        val longName = routeNode.path("longName").asText()
        val mode = routeNode.path("mode").asText()
        
        // Parse colors if available
        val color = parseColor(routeNode.path("color").asText())
        val textColor = parseColor(routeNode.path("textColor").asText())

        return Line(
            id = id,
            network = null,
            product = mapProduct(mode),
            label = shortName.ifEmpty { longName.take(4) }, // Use first 4 chars of long name if no short name
            name = longName.ifEmpty { shortName },
            style = null,
            attr = null,
            message = null
        ).apply {
            if (color != null) this.color = color
            if (textColor != null) this.textColor = textColor
        }
    }

    /**
     * Maps Digitransit mode to PTE Product.
     */
    private fun mapProduct(mode: String): Product {
        return when (mode.uppercase()) {
            "BUS" -> Product.BUS
            "TRAM" -> Product.TRAM
            "RAIL", "TRAIN" -> Product.REGIONAL_TRAIN
            "SUBWAY" -> Product.SUBWAY
            "FERRY" -> Product.FERRY
            else -> Product.BUS
        }
    }

    /**
     * Parses hex color string to integer.
     */
    private fun parseColor(colorStr: String?): Int? {
        if (colorStr.isNullOrEmpty()) return null
        
        return try {
            (0xFF000000 + colorStr.toLong(16)).toInt()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Adds seconds to a base date.
     * Used to convert "seconds since midnight" to actual timestamp.
     */
    private fun addSecondsToDate(baseDate: Date, seconds: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.time = baseDate
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.SECOND, seconds)
        return calendar.time
    }
}