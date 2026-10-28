package io.nekohasekai.sfa.compose.screen.settings

import io.nekohasekai.sfa.compose.theme.GlassTextButton
import io.nekohasekai.sfa.compose.theme.GlassActionButton
import io.nekohasekai.sfa.compose.theme.GlassIconButton
import io.nekohasekai.sfa.compose.theme.GlassOutlinedButton
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.bg.RootState
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.bg.ServiceWatchdog
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.launchCustomTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSettingsScreen(
    navController: NavController,
    serviceConnection: ServiceConnection? = null,
    serviceStatus: Status = Status.Stopped,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.service)) },
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
    val focusManager = LocalFocusManager.current
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }
    var allowBypass by remember { mutableStateOf(Settings.allowBypass) }
    var autoRestartEnabled by remember { mutableStateOf(ServiceWatchdog.autoRestartEnabled) }
    var airplaneRecoveryEnabled by remember { mutableStateOf(Settings.airplaneRecoveryEnabled) }

    // What the watcher currently uses, and the text being edited until "Apply" is pressed.
    var savedThreshold by remember { mutableStateOf(Settings.airplaneRecoveryFailureThreshold.coerceIn(THRESHOLD_RANGE)) }
    var savedCooldown by remember { mutableStateOf(Settings.airplaneRecoveryCooldownSec.coerceIn(COOLDOWN_RANGE)) }
    var thresholdInput by remember { mutableStateOf(savedThreshold.toString()) }
    var cooldownInput by remember { mutableStateOf(savedCooldown.toString()) }
    // Refreshed automatically every time the app is opened (MainActivity.onStart), so it is
    // already up to date here; no manual recheck needed.
    val rootState by RootClient.rootState.collectAsState()

    val thresholdValue = thresholdInput.toIntOrNull()?.takeIf { it in THRESHOLD_RANGE }
    val cooldownValue = cooldownInput.toIntOrNull()?.takeIf { it in COOLDOWN_RANGE }
    val canApply = thresholdValue != null &&
        cooldownValue != null &&
        (thresholdValue != savedThreshold || cooldownValue != savedCooldown)

    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    val requestBatteryOptimizationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(PowerManager::class.java)
                isBatteryOptimizationIgnored =
                    pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }

    // Saves both values. The running watcher picks them up by itself through the settings
    // listener in AirplaneModeRecovery, so no service restart is needed.
    val applyAirplaneConfig: () -> Unit = {
        if (thresholdValue != null && cooldownValue != null) {
            focusManager.clearFocus()
            savedThreshold = thresholdValue
            savedCooldown = cooldownValue
            thresholdInput = thresholdValue.toString()
            cooldownInput = cooldownValue.toString()
            scope.launch(Dispatchers.IO) {
                Settings.airplaneRecoveryFailureThreshold = thresholdValue
                Settings.airplaneRecoveryCooldownSec = cooldownValue
            }
            Toast.makeText(context, "Airplane recovery settings applied", Toast.LENGTH_SHORT).show()
        }
    }

    // Actively asks for root (this is what shows the su prompt of the root manager).
    val checkRoot: (Boolean) -> Unit = { warnWhenDenied ->
        if (rootState != RootState.Checking) {
            scope.launch {
                // RootClient publishes Checking / Granted / Denied itself.
                val granted = try {
                    RootClient.checkRootAvailable()
                } catch (e: Exception) {
                    false
                }
                if (!granted && warnWhenDenied) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.root_access_required),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(PowerManager::class.java)
            isBatteryOptimizationIgnored =
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            isBatteryOptimizationIgnored = true
        }
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            // Shrinks the scrolling area when the keyboard is open (the activity is edge-to-edge,
            // so it does not resize the window by itself). Must come before verticalScroll.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        if (!isBatteryOptimizationIgnored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            GlassCard(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                elevated = true,
                glowColor = GlassGlowAccent,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(
                            stringResource(R.string.background_permission),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }

                    Text(
                        stringResource(R.string.background_permission_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        GlassOutlinedButton(
                            onClick = {
                                context.launchCustomTab("https://dontkillmyapp.com/")
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(stringResource(R.string.read_more))
                        }

                        GlassActionButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val intent =
                                        Intent(
                                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                    requestBatteryOptimizationLauncher.launch(intent)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.request_background_permission))
                        }
                    }
                }
            }
        }

        // VPN Section
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "VPN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
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
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.allow_bypass),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.allow_bypass_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = allowBypass,
                        onCheckedChange = { checked ->
                            allowBypass = checked
                            scope.launch(Dispatchers.IO) {
                                Settings.allowBypass = checked
                                withContext(Dispatchers.Main) {
                                    notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
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

        // Auto restart (service watchdog)
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel("Auto restart")
        SettingsCard {
            ListItem(
                headlineContent = {
                    Text(
                        "Restart service when it dies",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        "Brings the VPN back if Android kills the service, retries when the core fails to " +
                            "start, and keeps a background alarm watching it. Airplane recovery comes back " +
                            "with the service. Stops trying after repeated crashes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = autoRestartEnabled,
                        onCheckedChange = { checked ->
                            autoRestartEnabled = checked
                            scope.launch(Dispatchers.IO) {
                                ServiceWatchdog.autoRestartEnabled = checked
                            }
                        },
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // Airplane mode recovery (root)
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel("Airplane recovery")
        SettingsCard {
            ListItem(
                headlineContent = {
                    Text(
                        "Enable airplane recovery",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        "Toggles airplane mode via root when every outbound of a urltest group keeps " +
                            "failing. Needs a urltest group in the config and works in the background.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.AirplanemodeActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = airplaneRecoveryEnabled,
                        onCheckedChange = { checked ->
                            airplaneRecoveryEnabled = checked
                            scope.launch(Dispatchers.IO) {
                                Settings.airplaneRecoveryEnabled = checked
                            }
                            // Turning it on is the moment root is needed: ask for it right away
                            // so the user sees the prompt and the indicator below.
                            if (checked && rootState != RootState.Granted) {
                                checkRoot(true)
                            }
                        },
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            RootAccessItem(
                state = rootState,
                onCheck = { checkRoot(false) },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            ListItem(
                headlineContent = {
                    Text(
                        "Trigger settings",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        "Press Apply to save the values and use them right away, no service restart needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberSettingField(
                    value = thresholdInput,
                    onValueChange = { thresholdInput = it },
                    label = "Failure threshold (1–20)",
                    hint = "Roughly how many seconds every URL test must keep failing before recovery " +
                        "runs. A fresh test is run first, so a short blip does not toggle anything. " +
                        "Default 5.",
                    error = if (thresholdValue == null) "Enter a number from 1 to 20" else null,
                    maxLength = 3,
                    imeAction = ImeAction.Next,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                NumberSettingField(
                    value = cooldownInput,
                    onValueChange = { cooldownInput = it },
                    label = "Cooldown seconds (1–900)",
                    hint = "Time between toggles (at least 8 s in practice). The value is used as " +
                        "entered for every toggle, it never grows. Default 8.",
                    error = if (cooldownValue == null) "Enter a number from 1 to 900" else null,
                    maxLength = 4,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (canApply) applyAirplaneConfig() else focusManager.clearFocus()
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    GlassActionButton(
                        onClick = applyAirplaneConfig,
                        enabled = canApply,
                    ) {
                        Text("Apply")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private val THRESHOLD_RANGE = 1..20
private val COOLDOWN_RANGE = 1..900

private val RootGrantedColor = Color(0xFF4CAF50)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevated = true,
        glowColor = GlassGlowAccent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(content = content)
    }
}

/** Status row showing whether this app has been granted root, with a button to (re)check it. */
@Composable
private fun RootAccessItem(
    state: RootState,
    onCheck: () -> Unit,
) {
    val statusColor = when (state) {
        RootState.Granted -> RootGrantedColor
        RootState.Denied -> MaterialTheme.colorScheme.error
        RootState.Unknown, RootState.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (state) {
        RootState.Unknown -> "Not checked yet"
        RootState.Checking -> "Checking…"
        RootState.Granted -> "Granted"
        RootState.Denied -> "Not granted"
    }
    val hint = when (state) {
        RootState.Unknown -> "Checked automatically every time you open the app."
        RootState.Denied ->
            "Airplane recovery can't toggle airplane mode without it. Grant root to this app in " +
                "your root manager; it is re-checked every time you open the app (or tap Check)."
        RootState.Checking, RootState.Granted -> null
    }

    ListItem(
        headlineContent = {
            Text(
                "Root access",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = when (state) {
                    RootState.Granted -> Icons.Filled.CheckCircle
                    RootState.Denied -> Icons.Filled.Error
                    RootState.Unknown, RootState.Checking -> Icons.Outlined.AdminPanelSettings
                },
                contentDescription = null,
                tint = statusColor,
            )
        },
        trailingContent = {
            when (state) {
                RootState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                // Granted needs no button any more: it is re-checked on every app open.
                RootState.Granted -> Unit
                RootState.Unknown, RootState.Denied -> GlassTextButton(onClick = onCheck) {
                    Text("Check")
                }
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Digits-only field. When it gains focus it asks its parent scroll container to bring it into
 * view once the keyboard animation has finished, so the keyboard never covers what is being typed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    error: String?,
    maxLength: Int,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            if (text.length <= maxLength && text.all { it.isDigit() }) {
                onValueChange(text)
            }
        },
        label = { Text(label) },
        isError = error != null,
        supportingText = { Text(error ?: hint) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    scope.launch {
                        delay(KEYBOARD_ANIMATION_MS)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            },
    )
}

private const val KEYBOARD_ANIMATION_MS = 350L
