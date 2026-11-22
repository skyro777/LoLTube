package com.loltube.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.loltube.app.player.PlayerViewModel
import com.loltube.app.providers.PlaybackRequest
import com.loltube.app.providers.ProviderId

private const val ARG_TMDB = "tmdb"

@UnstableApi
@Composable
fun AppNav() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as Application

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenDetails = { imdbId, type ->
                    navController.navigate("details/$imdbId/$type")
                },
                onPlay = { imdbId, type, season, episode ->
                    val s = season ?: -1
                    val e = episode ?: -1
                    navController.navigate("player/$imdbId/$type?s=$s&e=$e&provider=AUTO&startAt=0")
                },
                onOpenSettings = { navController.navigate("settings") },
            )
        }

        composable(
            route = "details/{imdb}/{type}",
            arguments = listOf(
                navArgument("imdb") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType },
            ),
        ) { entry ->
            val imdb = entry.arguments?.getString("imdb") ?: return@composable
            val type = entry.arguments?.getString("type") ?: "movie"
            DetailsScreen(
                imdbId = imdb,
                type = type,
                onBack = { navController.popBackStack() },
                onPlay = { imdbId, t, season, episode, provider, startAtMs ->
                    val s = season ?: -1
                    val e = episode ?: -1
                    val p = provider?.name ?: "AUTO"
                    navController.navigate(
                        "player/$imdbId/$t?s=$s&e=$e&provider=$p&startAt=${startAtMs / 1000}"
                    )
                },
            )
        }

        composable(
            route = "player/{imdb}/{type}?s={s}&e={e}&provider={provider}&startAt={startAt}",
            arguments = listOf(
                navArgument("imdb") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType },
                navArgument("s") { type = NavType.IntType; defaultValue = -1 },
                navArgument("e") { type = NavType.IntType; defaultValue = -1 },
                navArgument("provider") { type = NavType.StringType; defaultValue = "AUTO" },
                navArgument("startAt") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { entry ->
            val imdb = entry.arguments?.getString("imdb") ?: return@composable
            val type = entry.arguments?.getString("type") ?: "movie"
            val s = entry.arguments?.getInt("s") ?: -1
            val e = entry.arguments?.getInt("e") ?: -1
            val providerName = entry.arguments?.getString("provider") ?: "AUTO"
            val startAt = entry.arguments?.getLong("startAt") ?: 0L
            val provider = ProviderId.entries.firstOrNull { it.name == providerName }

            val request = PlaybackRequest(
                imdbId = imdb,
                type = type,
                season = s.takeIf { it > 0 },
                episode = e.takeIf { it > 0 },
                startAtMs = startAt * 1000,
            )

            val vm: PlayerViewModel = viewModel(
                key = "player-$imdb-$type-$s-$e-${provider?.name ?: 'A'}",
                factory = PlayerViewModel.factory(app, request, provider),
            )
            PlayerScreen(
                vm = vm,
                request = request,
                onBack = { navController.popBackStack() },
                onOpenWebView = { url ->
                    navController.navigate("webview?u=${Uri.encode(url)}")
                },
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLogs = { navController.navigate("logs") },
            )
        }

        composable("logs") {
            LogsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "webview?u={u}",
            arguments = listOf(navArgument("u") { type = NavType.StringType }),
        ) { entry ->
            val url = entry.arguments?.getString("u") ?: return@composable
            WebViewPlayerScreen(url = url, onBack = { navController.popBackStack() })
        }
    }
}
