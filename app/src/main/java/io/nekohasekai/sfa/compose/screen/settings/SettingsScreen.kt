package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Tune
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import io.nekohasekai.sfa.compose.theme.OnGlassPrimary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_settings)) },
        )
    }

    val scope = rememberCoroutineScope()

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        // General Settings Group
        GlassCard(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevated = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.title_app_settings),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .clickable { navController.navigate("settings/app") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.core),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier =
                    Modifier
                        .clickable { navController.navigate("settings/core") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.service),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable { navController.navigate("settings/service") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.profile_override),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.FilterAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier =
                    Modifier
                        .clickable { navController.navigate("settings/profile_override") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.remote_control),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.SettingsRemote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                        .clickable { navController.navigate("settings/remote_control") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
