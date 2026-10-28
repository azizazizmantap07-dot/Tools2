package io.nekohasekai.sfa.compose.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import io.nekohasekai.sfa.R

sealed class Screen(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    /** Line-style icon shown while the tab is not selected (glass bottom bar). */
    val outlinedIcon: ImageVector = icon,
) {
    object Dashboard : Screen(
        route = "dashboard",
        titleRes = R.string.title_dashboard,
        icon = Icons.Default.Dashboard,
        outlinedIcon = Icons.Outlined.Dashboard,
    )

    object Log : Screen(
        route = "log",
        titleRes = R.string.title_log,
        icon = Icons.AutoMirrored.Default.TextSnippet,
        outlinedIcon = Icons.AutoMirrored.Outlined.TextSnippet,
    )

    object Groups : Screen(
        route = "groups",
        titleRes = R.string.title_groups,
        icon = Icons.Default.Folder,
    )

    object Connections : Screen(
        route = "connections",
        titleRes = R.string.title_connections,
        icon = Icons.Default.SwapVert,
    )

    object Tools : Screen(
        route = "tools",
        titleRes = R.string.title_tools,
        icon = Icons.Default.Terminal,
        outlinedIcon = Icons.Outlined.Terminal,
    )

    object Settings : Screen(
        route = "settings",
        titleRes = R.string.title_settings,
        icon = Icons.Default.Settings,
        outlinedIcon = Icons.Outlined.Settings,
    )
}

val bottomNavigationScreens =
    listOf(
        Screen.Dashboard,
        Screen.Log,
        Screen.Tools,
        Screen.Settings,
    )
