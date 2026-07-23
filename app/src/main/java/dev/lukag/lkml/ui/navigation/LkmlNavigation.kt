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

package dev.lukag.lkml.ui.navigation

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.lukag.lkml.data.remote.LoreUrls
import dev.lukag.lkml.ui.feed.FeedScreen
import dev.lukag.lkml.ui.lists.ListCatalogScreen
import dev.lukag.lkml.ui.saved.SavedScreen
import dev.lukag.lkml.ui.search.SearchScreen
import dev.lukag.lkml.ui.thread.ThreadScreen
import kotlin.text.Charsets.UTF_8

private object Routes {
    const val LISTS = "lists"
    const val SEARCH = "search"
    const val SAVED = "saved"
    const val FEED = "feed/{listSlug}"
    const val THREAD = "thread/{rootMessageId}"

    /**
     * List slugs go into the route verbatim.
     *
     * Every one of the ~350 slugs in lore's manifest matches `[A-Za-z0-9._-]+`, so none
     * contains a character that is structural in a route. (Message-IDs are a different
     * story — see [MessageIdRoute].)
     */
    fun feed(listSlug: String) = "feed/$listSlug"

    fun thread(rootMessageId: String) = "thread/${MessageIdRoute.encode(rootMessageId)}"
}

/**
 * Route-safe encoding for Message-IDs, shared with `ThreadViewModel`.
 *
 * Percent-encoding is the obvious choice but not a safe one: Navigation decodes string
 * path arguments itself, so an encoded ID would be decoded once by the library and, if
 * the caller decoded again, an ID containing a literal `%` would be corrupted. URL-safe
 * Base64 sidesteps the question — the segment contains no reserved characters, so nothing
 * along the way is tempted to interpret it.
 */
object MessageIdRoute {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun encode(messageId: String): String =
        Base64.encodeToString(messageId.toByteArray(UTF_8), FLAGS)

    fun decode(encoded: String): String =
        runCatching { String(Base64.decode(encoded, FLAGS), UTF_8) }.getOrDefault(encoded)
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TOP_LEVEL = listOf(
    TopLevelDestination(Routes.LISTS, "Lists", Icons.Default.Forum),
    TopLevelDestination(Routes.SEARCH, "Search", Icons.Default.Search),
    TopLevelDestination(Routes.SAVED, "Saved", Icons.Default.Bookmarks),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LkmlApp(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in TOP_LEVEL.map { it.route }

    // Built from the thread's own list, not the aggregate: `/all/` lags the per-list
    // indexes, so an aggregate permalink 404s for exactly the recent threads a reader is
    // most likely to want to open in a browser.
    val openInBrowser: (String, String) -> Unit = { listSlug, messageId ->
        val url = LoreUrls.permalink(listSlug, messageId)
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    Scaffold(
        topBar = {
            if (isTopLevel) {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentRoute) {
                                Routes.LISTS -> "Mailing lists"
                                Routes.SEARCH -> "Search"
                                Routes.SAVED -> "Saved"
                                else -> "LKML"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TOP_LEVEL.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // Standard bottom-nav behaviour: one entry per tab,
                                    // state preserved when switching back and forth.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LISTS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LISTS) {
                ListCatalogScreen(
                    onOpenList = { navController.navigate(Routes.feed(it.slug)) },
                )
            }
            composable(
                route = Routes.FEED,
                arguments = listOf(navArgument("listSlug") { type = NavType.StringType }),
            ) {
                FeedScreen(
                    onBack = { navController.popBackStack() },
                    onOpenThread = { navController.navigate(Routes.thread(it.rootMessageId)) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenThread = { navController.navigate(Routes.thread(it)) },
                )
            }
            composable(Routes.SAVED) {
                SavedScreen(
                    onOpenThread = { navController.navigate(Routes.thread(it)) },
                )
            }
            composable(
                route = Routes.THREAD,
                arguments = listOf(navArgument("rootMessageId") { type = NavType.StringType }),
            ) {
                // The argument stays encoded all the way into SavedStateHandle;
                // ThreadViewModel decodes it. Rewriting the arguments Bundle here would
                // race with ViewModel creation.
                ThreadScreen(
                    onBack = { navController.popBackStack() },
                    onOpenInBrowser = openInBrowser,
                )
            }
        }
    }
}
