package io.nekohasekai.sfa.compose.screen.settings

import io.nekohasekai.sfa.compose.theme.GlassTextButton
import io.nekohasekai.sfa.compose.theme.GlassIconButton
import io.nekohasekai.sfa.compose.theme.GlassPopup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SmartToy
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.vendor.PackageQueryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.GlassAlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.profile_override)) },
            navigationIcon = {
                GlassIconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoRedirect by remember { mutableStateOf(Settings.autoRedirect) }
    var perAppProxyEnabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var managedModeEnabled by remember { mutableStateOf(Settings.perAppProxyManagedMode) }
    var isScanning by remember { mutableStateOf(false) }
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    fun scanAndSaveManagedList(shouldNotify: Boolean = false) {
        isScanning = true
        scope.launch {
            val chinaApps = PerAppProxyScanner.scanAllChinaApps()
            withContext(Dispatchers.IO) {
                Settings.perAppProxyManagedList = chinaApps
            }
            isScanning = false
            if (shouldNotify) {
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
            }
        }
    }

    var showRootDialog by remember { mutableStateOf(false) }

    val requiresRoot = PackageQueryManager.requiresRoot

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
        // Card 1: Auto Redirect
        GlassCard(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevated = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.auto_redirect),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.auto_redirect_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = autoRedirect,
                        onCheckedChange = { checked ->
                            if (checked && !autoRedirect) {
                                scope.launch {
                                    val hasRoot = RootClient.checkRootAvailable()
                                    if (hasRoot) {
                                        autoRedirect = true
                                        withContext(Dispatchers.IO) {
                                            Settings.autoRedirect = true
                                        }
                                        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.root_access_required),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            } else if (!checked) {
                                autoRedirect = false
                                scope.launch(Dispatchers.IO) {
                                    Settings.autoRedirect = false
                                    withContext(Dispatchers.Main) {
                                        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                    }
                                }
                            }
                        },
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                colors =
                ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
            )
        }

        // Section: Per-App Proxy
        Text(
            text = stringResource(R.string.per_app_proxy),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
        )

        GlassCard(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevated = true,
            glowColor = GlassGlowAccent,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Column {
                // Enabled toggle
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.enabled),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.FilterList,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = perAppProxyEnabled,
                            onCheckedChange = { checked ->
                                if (checked && requiresRoot) {
                                    showRootDialog = true
                                } else {
                                    perAppProxyEnabled = checked
                                    scope.launch(Dispatchers.IO) {
                                        Settings.perAppProxyEnabled = checked
                                        if (!checked || !managedModeEnabled) {
                                            withContext(Dispatchers.Main) {
                                                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                            }
                                        }
                                    }
                                    if (checked && managedModeEnabled) {
                                        scanAndSaveManagedList(shouldNotify = true)
                                    }
                                }
                            },
                            enabled = !isScanning,
                        )
                    },
                    modifier =
                    Modifier.clip(
                        if (perAppProxyEnabled) {
                            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        } else {
                            RoundedCornerShape(12.dp)
                        },
                    ),
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                if (perAppProxyEnabled) {
                    // Manage entry
                    val manageEnabled = !managedModeEnabled
                    val disabledAlpha = 0.38f
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.per_app_proxy_manage),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (manageEnabled) {
                                    Color.Unspecified
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                                },
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.AppShortcut,
                                contentDescription = null,
                                tint = if (manageEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                                },
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = if (manageEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                                },
                            )
                        },
                        modifier =
                        Modifier.clickable(enabled = manageEnabled) {
                            navController.navigate("settings/profile_override/manage")
                        },
                        colors =
                        ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )

                    // Managed Mode toggle
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.per_app_proxy_managed_mode),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.per_app_proxy_managed_mode_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Switch(
                                    checked = managedModeEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            managedModeEnabled = true
                                            scope.launch(Dispatchers.IO) {
                                                Settings.perAppProxyManagedMode = true
                                            }
                                            scanAndSaveManagedList(shouldNotify = true)
                                        } else {
                                            managedModeEnabled = false
                                            scope.launch(Dispatchers.IO) {
                                                Settings.perAppProxyManagedMode = false
                                                withContext(Dispatchers.Main) {
                                                    notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        },
                        modifier = Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                        colors =
                        ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }

        // ROOT dialog
        if (showRootDialog) {
            GlassAlertDialog(
                onDismissRequest = { showRootDialog = false },
                title = {
                    Text(stringResource(R.string.per_app_proxy))
                },
                text = {
                    Text(stringResource(R.string.per_app_proxy_root_required))
                },
                confirmButton = {
                    GlassTextButton(
                        onClick = {
                            scope.launch {
                                val hasRoot = PackageQueryManager.checkRootAvailable()
                                if (hasRoot) {
                                    showRootDialog = false
                                    perAppProxyEnabled = true
                                    withContext(Dispatchers.IO) {
                                        Settings.perAppProxyEnabled = true
                                    }
                                    if (managedModeEnabled) {
                                        scanAndSaveManagedList(shouldNotify = true)
                                    } else {
                                        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                    }
                                } else {
                                    showRootDialog = false
                                    Toast.makeText(
                                        context,
                                        R.string.root_access_denied,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    GlassTextButton(
                        onClick = { showRootDialog = false },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
