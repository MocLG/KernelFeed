/*
 * KernelFeed — an offline-first reader for the lore.kernel.org mailing-list archives.
 * Copyright (C) 2026 Luka Gejak
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3, as published
 * by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Alternatively, this file is available under a commercial licence that lifts
 * the obligations of the GPL. Enquiries: lukagejak5@gmail.com
 */

package dev.lukag.kernelfeed.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes connectivity so the UI can distinguish "failed" from "offline".
 *
 * Uses `NET_CAPABILITY_VALIDATED` rather than mere availability: a captive-portal Wi-Fi
 * network is "connected" but cannot reach lore, and reporting it as online produces the
 * worst failure mode — an error screen sitting on top of perfectly good cached data.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService<ConnectivityManager>()

    val isOnline: Flow<Boolean> = callbackFlow {
        val cm = manager
        if (cm == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            private val online = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                online += network
                trySend(true)
            }

            override fun onLost(network: Network) {
                online -= network
                trySend(online.isNotEmpty())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable) online += network else online -= network
                trySend(online.isNotEmpty())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        trySend(currentlyOnline())

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()

    fun currentlyOnline(): Boolean {
        val cm = manager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal companion object {
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
