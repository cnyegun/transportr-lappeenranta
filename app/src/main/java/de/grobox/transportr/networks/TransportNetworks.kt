/*
 *    Lappeenranta Bussi (Fork of Transportr)
 *
 *    Simplified to only support Finland via Digitransit API
 *
 *    TODO: Fork PTE library and add NetworkId.FI enum value
 *    For now, using NetworkId.SE (Sweden) as placeholder since we're
 *    stripping out all other networks anyway
 */

package de.grobox.transportr.networks

import android.annotation.SuppressLint
import android.content.Context
import de.grobox.transportr.BuildConfig
import de.grobox.transportr.R
import de.schildbach.pte.NetworkId

@SuppressLint("ConstantLocale")
private val networks = arrayOf(
    Continent(
        R.string.np_continent_europe, R.drawable.continent_europe,
        listOf(
            Country(
                R.string.np_region_finland, flag = "🇫🇮", networks = listOf(
                    TransportNetwork(
                        id = NetworkId.SE, // TODO: Change to FI after forking PTE
                        name = R.string.np_name_fi,
                        description = R.string.np_desc_fi,
                        agencies = R.string.np_desc_fi_networks,
                        logo = R.drawable.network_fi_logo,
                        status = TransportNetwork.Status.BETA,
                        factory = { 
                            DigitransitProvider(
                                apiKey = BuildConfig.DIGITRANSIT_API_KEY
                            )
                        }
                    )
                )
            )
        )
    )
)

internal fun getContinentItems(context: Context): List<ContinentItem> {
    return List(networks.size) { i ->
        networks[i].getItem(context)
    }.sortedBy { it.getName(context) }
}

internal fun getTransportNetwork(id: NetworkId): TransportNetwork? {
    for (continent in networks) {
        return continent.getTransportNetworks().find { it.id == id } ?: continue
    }
    return null
}

internal fun getTransportNetworkPositions(context: Context, network: TransportNetwork): Triple<Int, Int, Int> {
    val continents = networks.sortedBy { it.getName(context) }.withIndex()
    for ((continentIndex, continent) in continents) {
        val countries = continent.countries.sortedWith(Country.Comparator(context)).withIndex()
        for ((countryIndex, country) in countries) {
            val networkIndex = country.networks.indexOf(network)
            if (networkIndex > -1) {
                return Triple(continentIndex, countryIndex, networkIndex)
            }
        }
    }
    return Triple(-1, -1, -1)
}