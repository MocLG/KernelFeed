/*
 * LKML — an offline-first reader for the lore.kernel.org mailing-list archives.
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

package dev.lukag.lkml.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lukag.lkml.core.AppError

/** Human phrasing for every [AppError], including the one nobody expects to hit. */
fun AppError.describe(): String = when (this) {
    is AppError.Network -> "Couldn't reach lore.kernel.org."
    is AppError.Http -> when (code) {
        403 -> "lore.kernel.org refused the request (403)."
        404 -> "That thread isn't in the archive."
        429 -> "Too many requests — give the archive a moment."
        in 500..599 -> "lore.kernel.org is having trouble ($code)."
        else -> "Request failed ($code)."
    }
    // Worth naming precisely: it is a client-configuration fault, not a server outage,
    // and saying "network error" would send anyone debugging it down the wrong path.
    AppError.BotChallenge -> "The archive served a bot check instead of data."
    is AppError.Parse -> "Unexpected response from the archive."
    is AppError.Storage -> "Couldn't read the local cache."
    is AppError.Unknown -> cause ?: "Something went wrong."
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "Offline — showing saved content",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun ErrorBanner(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        ) {
            Text(
                text = error.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            onRetry?.let { TextButton(onClick = it) { Text("Retry") } }
            onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
        }
    }
}

/** Full-screen failure, used only when there is nothing cached to show underneath. */
@Composable
fun ErrorState(
    error: AppError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = error.describe(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Try again")
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
