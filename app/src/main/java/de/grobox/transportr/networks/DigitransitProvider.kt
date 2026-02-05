/*
 *    Lappeenranta Bussi - DigitransitProvider
 *    
 *    Implementation of NetworkProvider for Digitransit API (Finland).
 *    Uses GraphQL for all operations.
 *    
 *    Architecture:
 *    - Extends AbstractNetworkProvider (PTE standard)
 *    - Uses OkHttp for HTTP requests
 *    - Jackson for JSON parsing
 *    - Separate mapper classes for data transformation
 */

package de.grobox.transportr.networks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import de.schildbach.pte.AbstractNetworkProvider
import de.schildbach.pte.NetworkId
import de.schildbach.pte.NetworkProvider
import de.schildbach.pte.dto.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Digitransit provider for Finland public transport.
 * 
 * ASSUMPTIONS:
 * - API returns GraphQL responses
 * - Requires digitransit-subscription-key header
 * - Finland endpoint covers Lappeenranta
 * - Real-time data available when trip has realtimeState
 */
class DigitransitProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String
) : AbstractNetworkProvider() {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.digitransit.fi/routing/v2/finland/gtfs/v1"
        const val GRAPHQL_ENDPOINT = "/index/graphql"
        
        // Maximum walking distance for "too close" check (in meters)
        const val MIN_TRIP_DISTANCE_METERS = 100
        
        // Request timeout
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    // TODO: Change to NetworkId.FI after forking PTE library and adding FI enum
    override fun id(): NetworkId = NetworkId.SE

    override fun hasCapabilities(vararg capabilities: NetworkProvider.Capability): Boolean {
        return capabilities.any { capability ->
            when (capability) {
                NetworkProvider.Capability.TRIPS,
                NetworkProvider.Capability.TRIPS_VIA,
                NetworkProvider.Capability.DEPARTURES,
                NetworkProvider.Capability.SUGGEST_LOCATIONS,
                NetworkProvider.Capability.NEARBY_LOCATIONS -> true
                else -> false
            }
        }
    }

    // ============================================================================
    // TRIP PLANNING (ROUTING)
    // ============================================================================

    override fun queryTrips(
        from: Location,
        via: Location?,
        to: Location,
        date: Date,
        dep: Boolean,
        products: Set<Product>?,
        optimize: Trip.Optimize?,
        walkSpeed: Trip.WalkSpeed?,
        accessibility: Trip.Accessibility?,
        options: Set<NetworkProvider.Option>?
    ): QueryTripsResult {
        
        // Edge case: Check if locations are too close
        if (areLocationsTooClose(from, to)) {
            return QueryTripsResult(QueryTripsResult.Status.TOO_CLOSE, QueryTripsContext.EMPTY, emptyList())
        }

        return try {
            val query = buildTripPlanningQuery(from, via, to, date, dep, products)
            val response = executeGraphQLQuery(query)
            
            if (response == null) {
                return QueryTripsResult(
                    QueryTripsResult.Status.SERVICE_DOWN,
                    QueryTripsContext.EMPTY,
                    emptyList()
                )
            }

            // Check for GraphQL errors
            if (response.has("errors")) {
                val errorCode = parseErrorCode(response)
                return when (errorCode) {
                    "AMBIGUOUS_LOCATION" -> QueryTripsResult(
                        QueryTripsResult.Status.AMBIGUOUS,
                        QueryTripsContext.EMPTY,
                        emptyList()
                    )
                    else -> QueryTripsResult(
                        QueryTripsResult.Status.SERVICE_DOWN,
                        QueryTripsContext.EMPTY,
                        emptyList()
                    )
                }
            }

            // Parse successful response
            val planNode = response.path("data").path("plan")
            if (planNode.isMissingNode || planNode.isNull) {
                return QueryTripsResult(
                    QueryTripsResult.Status.NO_TRIPS,
                    QueryTripsContext.EMPTY,
                    emptyList()
                )
            }

            val itineraries = planNode.path("itineraries")
            if (!itineraries.isArray || itineraries.size() == 0) {
                return QueryTripsResult(
                    QueryTripsResult.Status.NO_TRIPS,
                    QueryTripsContext.EMPTY,
                    emptyList()
                )
            }

            // Convert itineraries to PTE Trips
            val trips = mutableListOf<Trip>()
            val context = QueryTripsContext.EMPTY // For pagination (future)

            for (i in 0 until itineraries.size()) {
                val itineraryNode = itineraries[i]
                val trip = TripMapper.mapToTrip(itineraryNode, from, to)
                trips.add(trip)
            }

            QueryTripsResult(QueryTripsResult.Status.OK, context, trips)

        } catch (e: IOException) {
            QueryTripsResult(
                QueryTripsResult.Status.SERVICE_DOWN,
                QueryTripsContext.EMPTY,
                emptyList()
            )
        } catch (e: Exception) {
            QueryTripsResult(
                QueryTripsResult.Status.SERVICE_DOWN,
                QueryTripsContext.EMPTY,
                emptyList()
            )
        }
    }

    override fun queryMoreTrips(context: QueryTripsContext, later: Boolean): QueryTripsResult {
        // TODO: Implement pagination support
        // For now, return empty result
        return QueryTripsResult(
            QueryTripsResult.Status.NO_TRIPS,
            QueryTripsContext.EMPTY,
            emptyList()
        )
    }

    // ============================================================================
    // DEPARTURES
    // ============================================================================

    override fun queryDepartures(
        stationId: String,
        date: Date,
        maxDepartures: Int,
        equiv: Boolean
    ): QueryDeparturesResult {
        
        return try {
            val query = buildDeparturesQuery(stationId, maxDepartures)
            val response = executeGraphQLQuery(query)
            
            if (response == null) {
                return QueryDeparturesResult(null, emptyList())
            }

            val stopNode = response.path("data").path("stop")
            if (stopNode.isMissingNode || stopNode.isNull) {
                return QueryDeparturesResult(null, emptyList())
            }

            val departures = DepartureMapper.mapToDepartures(stopNode, stationId)
            
            QueryDeparturesResult(
                null,
                listOf(StationDepartures(Location(LocationType.STATION, stationId), departures, null))
            )

        } catch (e: Exception) {
            QueryDeparturesResult(null, emptyList())
        }
    }

    // ============================================================================
    // LOCATION SEARCH
    // ============================================================================

    override fun suggestLocations(
        constraint: CharSequence,
        types: Set<LocationType>,
        maxLocations: Int
    ): SuggestLocationsResult {
        
        return try {
            val query = buildGeocodingQuery(constraint.toString(), maxLocations)
            val response = executeGraphQLQuery(query)
            
            if (response == null) {
                return SuggestLocationsResult(emptyList())
            }

            val geocodeNode = response.path("data").path("geocode")
            if (geocodeNode.isMissingNode || geocodeNode.isNull) {
                return SuggestLocationsResult(emptyList())
            }

            val locations = LocationMapper.mapGeocodingToLocations(geocodeNode)
            
            // Convert to SuggestedLocation with priority
            val suggestedLocations = locations.map { location ->
                SuggestedLocation(location, 0) // Priority 0 = no priority
            }
            
            SuggestLocationsResult(suggestedLocations)

        } catch (e: Exception) {
            SuggestLocationsResult(emptyList())
        }
    }

    // ============================================================================
    // NEARBY LOCATIONS
    // ============================================================================

    override fun queryNearbyLocations(
        location: Location,
        maxDistance: Int,
        maxLocations: Int
    ): NearbyLocationsResult {
        
        return try {
            val query = buildNearbyQuery(location, maxDistance, maxLocations)
            val response = executeGraphQLQuery(query)
            
            if (response == null) {
                return NearbyLocationsResult(emptyList())
            }

            val nearestNode = response.path("data").path("nearest")
            if (nearestNode.isMissingNode || nearestNode.isNull) {
                return NearbyLocationsResult(emptyList())
            }

            val locations = LocationMapper.mapNearbyToLocations(nearestNode)
            NearbyLocationsResult(locations)

        } catch (e: Exception) {
            NearbyLocationsResult(emptyList())
        }
    }

    // ============================================================================
    // UTILITY METHODS
    // ============================================================================

    override fun getArea(): Array<Point> {
        // Finland bounding box (rough approximation)
        // Lappeenranta is at approximately 61.0586°N, 28.1887°E
        return arrayOf(
            Point(59.0, 19.0),  // Southwest
            Point(70.0, 32.0)   // Northeast
        )
    }

    override fun lineStyle(line: Line): Style {
        // Use line color if available, otherwise default
        val color = line.color ?: 0xFF0000 // Red default
        val textColor = line.textColor ?: 0xFFFFFF // White default
        return Style(Style.Shape.RECT, color, textColor)
    }

    // ============================================================================
    // PRIVATE HELPER METHODS
    // ============================================================================

    /**
     * Executes a GraphQL query against the Digitransit API.
     * 
     * @param query The GraphQL query string
     * @return JsonNode with the response, or null if request failed
     */
    private fun executeGraphQLQuery(query: String): JsonNode? {
        val requestBody = objectMapper.writeValueAsString(mapOf("query" to query))
        
        val request = Request.Builder()
            .url("$baseUrl$GRAPHQL_ENDPOINT")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("digitransit-subscription-key", apiKey)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return null
            }

            return objectMapper.readTree(responseBody)
        }
    }

    /**
     * Checks if two locations are within minimum trip distance.
     * Used to detect "too close" error.
     */
    private fun areLocationsTooClose(from: Location, to: Location): Boolean {
        if (from.lat == 0 || from.lon == 0 || to.lat == 0 || to.lon == 0) {
            return false // Can't determine without coordinates
        }

        val distance = calculateDistance(from.lat, from.lon, to.lat, to.lon)
        return distance < MIN_TRIP_DISTANCE_METERS
    }

    /**
     * Calculates distance between two coordinates using Haversine formula.
     * Returns distance in meters.
     */
    private fun calculateDistance(lat1: Int, lon1: Int, lat2: Int, lon2: Int): Double {
        val R = 6371000 // Earth radius in meters
        
        val lat1Rad = Math.toRadians(lat1 / 1e6)
        val lat2Rad = Math.toRadians(lat2 / 1e6)
        val deltaLat = Math.toRadians((lat2 - lat1) / 1e6)
        val deltaLon = Math.toRadians((lon2 - lon1) / 1e6)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return R * c
    }

    /**
     * Extracts error code from GraphQL error response.
     */
    private fun parseErrorCode(response: JsonNode): String? {
        val errors = response.path("errors")
        if (errors.isArray && errors.size() > 0) {
            val firstError = errors[0]
            return firstError.path("extensions").path("code").asText()
        }
        return null
    }

    // ============================================================================
    // GRAPHQL QUERY BUILDERS
    // ============================================================================

    private fun buildTripPlanningQuery(
        from: Location,
        via: Location?,
        to: Location,
        date: Date,
        dep: Boolean,
        products: Set<Product>?
    ): String {
        
        val fromCoords = locationToCoords(from)
        val toCoords = locationToCoords(to)
        
        // Format date and time for GraphQL
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"))
        calendar.time = date
        
        val dateStr = String.format(
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        
        val timeStr = String.format(
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )

        return """
            query {
                plan(
                    from: {lat: ${fromCoords.first}, lon: ${fromCoords.second}}
                    to: {lat: ${toCoords.first}, lon: ${toCoords.second}}
                    date: "$dateStr"
                    time: "$timeStr"
                    arriveBy: ${!dep}
                    numItineraries: 5
                ) {
                    itineraries {
                        duration
                        walkDistance
                        legs {
                            mode
                            startTime
                            endTime
                            realTime
                            distance
                            from {
                                name
                                lat
                                lon
                                stop {
                                    gtfsId
                                    name
                                }
                            }
                            to {
                                name
                                lat
                                lon
                                stop {
                                    gtfsId
                                    name
                                }
                            }
                            route {
                                gtfsId
                                shortName
                                longName
                                mode
                                color
                                textColor
                            }
                            trip {
                                gtfsId
                                tripHeadsign
                            }
                            intermediatePlaces {
                                name
                                lat
                                lon
                                arrivalTime
                                stop {
                                    gtfsId
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }

    private fun buildDeparturesQuery(stationId: String, maxDepartures: Int): String {
        return """
            query {
                stop(id: "$stationId") {
                    name
                    gtfsId
                    stoptimesWithoutPatterns(numberOfDepartures: $maxDepartures) {
                        scheduledDeparture
                        realtimeDeparture
                        departureDelay
                        realtimeState
                        headsign
                        trip {
                            route {
                                gtfsId
                                shortName
                                longName
                                mode
                                color
                                textColor
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }

    private fun buildGeocodingQuery(query: String, maxResults: Int): String {
        return """
            query {
                geocode(
                    query: "$query"
                    size: $maxResults
                ) {
                    hits {
                        properties {
                            name
                            label
                            layer
                        }
                        geometry {
                            coordinates
                        }
                    }
                }
            }
        """.trimIndent()
    }

    private fun buildNearbyQuery(location: Location, maxDistance: Int, maxLocations: Int): String {
        val coords = locationToCoords(location)
        
        return """
            query {
                nearest(
                    lat: ${coords.first}
                    lon: ${coords.second}
                    maxDistance: $maxDistance
                    filterByPlaceTypes: STOP
                ) {
                    edges {
                        node {
                            distance
                            place {
                                ... on Stop {
                                    gtfsId
                                    name
                                    lat
                                    lon
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }

    /**
     * Converts Location to coordinate pair (lat, lon) in degrees.
     * PTE uses integer microdegrees (lat * 1e6), we need to convert.
     */
    private fun locationToCoords(location: Location): Pair<Double, Double> {
        val lat = location.lat / 1e6
        val lon = location.lon / 1e6
        return Pair(lat, lon)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}