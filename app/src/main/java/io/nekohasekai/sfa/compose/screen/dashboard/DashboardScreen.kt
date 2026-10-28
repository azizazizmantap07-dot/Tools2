package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.compose.theme.GlassIconButton
import io.nekohasekai.sfa.compose.theme.GlassPopup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.RemoteControlMenuItems
import io.nekohasekai.sfa.compose.component.rememberRemoteServers
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.launch
import io.nekohasekai.sfa.compose.theme.GlassDropdownMenu
import io.nekohasekai.sfa.compose.theme.LiquidScrollIntensity
import io.nekohasekai.sfa.compose.theme.liquidOverscroll
import io.nekohasekai.sfa.compose.theme.liquidScroll
import io.nekohasekai.sfa.compose.theme.liquidOverscrollItem
import io.nekohasekai.sfa.compose.theme.rememberLiquidOverscrollState

data class CardRenderItem(val cards: List<CardGroup>, val isRow: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    serviceStatus: Status = Status.Stopped,
    showStartFab: Boolean = false,
    showStatusBar: Boolean = false,
    onOpenNewProfile: (NewProfileArgs) -> Unit = {},
    viewModel: DashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val isRemote = remoteServer != null
    val remoteServers by rememberRemoteServers()
    var showOthersMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val overscrollState = rememberLiquidOverscrollState()

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_dashboard)) },
            actions = {
                Box {
                    GlassIconButton(onClick = { showOthersMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.title_others),
                        )
                    }
                    GlassDropdownMenu(
                        expanded = showOthersMenu,
                        onDismissRequest = { showOthersMenu = false },
        containerColor = io.nekohasekai.sfa.compose.theme.GlassPopup,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dashboard_items)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.GridView,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                showOthersMenu = false
                                viewModel.toggleCardSettingsDialog()
                            },
                        )
                        RemoteControlMenuItems(
                            servers = remoteServers,
                            onAction = { showOthersMenu = false },
                        )
                    }
                }
            },
        )
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Show dashboard settings bottom sheet
    if (uiState.showCardSettingsDialog) {
        DashboardSettingsBottomSheet(
            sheetState = sheetState,
            visibleCards = uiState.visibleCards,
            cardOrder = uiState.cardOrder,
            onToggleCard = viewModel::toggleCardVisibility,
            onReorderCards = viewModel::reorderCards,
            onResetOrder = viewModel::resetCardOrder,
            onDismiss = {
                scope.launch {
                    sheetState.hide()
                    viewModel.closeCardSettingsDialog()
                }
            },
        )
    }

    if (isRemote && !remoteConnected) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        val bottomPadding = when {
            showStartFab -> 88.dp
            showStatusBar -> 74.dp
            else -> 0.dp
        }
        LazyColumn(
            state = listState,
            modifier =
            Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 16.dp)
                .liquidOverscroll(listState, overscrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // The small top padding gives the first card room to puff up on release; the list clips
            // everything above its top edge, which cut the rim of the first card.
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        ) {
            // Watchdog indicator (auto restart, airplane recovery, root access): always shown on
            // the local dashboard, also while the service is off, because it is about this
            // device. It does not apply to a remote server's dashboard.
            if (!isRemote) {
                item(key = "watchdog_status") {
                    WatchdogStatusCard(
                        serviceRunning = serviceStatus == Status.Starting || serviceStatus == Status.Started,
                    )
                }
            }

            // Dynamic dashboard cards
            // Show cards when service is running OR if it's the Profiles card (always available)
            val serviceRunning = uiState.isStatusVisible

            // Filter cards based on availability
            val actuallyVisibleCards =
                uiState.visibleCards.filter { cardGroup ->
                    when {
                        // The remote dashboard only renders cards backed by the
                        // command protocol: profiles and system proxy are
                        // operations on the local device.
                        isRemote ->
                            cardGroup != CardGroup.Profiles &&
                                cardGroup != CardGroup.SystemProxy &&
                                serviceRunning &&
                                isCardAvailableWhenServiceRunning(cardGroup, uiState)

                        cardGroup == CardGroup.Profiles -> true // Profiles card is always available
                        else -> serviceRunning && isCardAvailableWhenServiceRunning(cardGroup, uiState)
                    }
                }.toSet()

            // Process cards to group half-width cards together
            val cardRenderItems =
                processCardsForRendering(
                    cardOrder = uiState.cardOrder,
                    visibleCards = actuallyVisibleCards,
                )

            // Offset accounts for the optional watchdog item so liquidScroll index matches LazyColumn layout
            val listIndexOffset = if (!isRemote) 1 else 0
            itemsIndexed(cardRenderItems) { index, renderItem ->
                // Padding sits outside the transform so scaled/tilted glass rims stay
                // inside the layout box and are not clipped by LazyColumn.
                val itemScrollModifier =
                    Modifier
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .liquidScroll(listState, index + listIndexOffset, LiquidScrollIntensity.Extreme)
                        .liquidOverscrollItem(overscrollState, intensity = LiquidScrollIntensity.Extreme)
                if (renderItem.isRow && renderItem.cards.size >= 2) {
                    // Render two half-width cards in a row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(itemScrollModifier),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        renderItem.cards.forEach { cardGroup ->
                            DashboardCardRenderer(
                                cardGroup = cardGroup,
                                uiState = uiState,
                                onClashModeSelected = viewModel::selectClashMode,
                                onSystemProxyToggle = viewModel::toggleSystemProxy,
                                // Profile card specific props
                                profiles = uiState.profiles,
                                selectedProfileId = uiState.selectedProfileId,
                                isLoading = uiState.isLoading,
                                showAddProfileSheet = uiState.showAddProfileSheet,
                                showProfilePickerSheet = uiState.showProfilePickerSheet,
                                updatingProfileId = uiState.updatingProfileId,
                                updatedProfileId = uiState.updatedProfileId,
                                onProfileSelected = viewModel::selectProfile,
                                onProfileEdit = viewModel::editProfile,
                                onProfileDelete = viewModel::deleteProfile,
                                onProfileShare = viewModel::shareProfile,
                                onProfileShareURL = viewModel::shareProfileURL,
                                onProfileUpdate = viewModel::updateProfile,
                                onProfileMove = viewModel::moveProfile,
                                onShowAddProfileSheet = viewModel::showAddProfileSheet,
                                onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                                onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
                                onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
                                onOpenNewProfile = onOpenNewProfile,
                                commandClient = viewModel.commandClient,
                                modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    // Render single card (full-width or single half-width)
                    renderItem.cards.forEach { cardGroup ->
                        DashboardCardRenderer(
                            cardGroup = cardGroup,
                            uiState = uiState,
                            serviceStatus = serviceStatus,
                            onClashModeSelected = viewModel::selectClashMode,
                            onSystemProxyToggle = viewModel::toggleSystemProxy,
                            // Profile card specific props
                            profiles = uiState.profiles,
                            selectedProfileId = uiState.selectedProfileId,
                            isLoading = uiState.isLoading,
                            showAddProfileSheet = uiState.showAddProfileSheet,
                            showProfilePickerSheet = uiState.showProfilePickerSheet,
                            updatingProfileId = uiState.updatingProfileId,
                            updatedProfileId = uiState.updatedProfileId,
                            onProfileSelected = viewModel::selectProfile,
                            onProfileEdit = viewModel::editProfile,
                            onProfileDelete = viewModel::deleteProfile,
                            onProfileShare = viewModel::shareProfile,
                            onProfileShareURL = viewModel::shareProfileURL,
                            onProfileUpdate = viewModel::updateProfile,
                            onProfileMove = viewModel::moveProfile,
                            onShowAddProfileSheet = viewModel::showAddProfileSheet,
                            onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                            onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
                            onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
                            onOpenNewProfile = onOpenNewProfile,
                            commandClient = viewModel.commandClient,
                            modifier = itemScrollModifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Process cards for rendering, grouping consecutive cards of the same pair group into rows
 */
fun processCardsForRendering(
    cardOrder: List<CardGroup>,
    visibleCards: Set<CardGroup>,
): List<CardRenderItem> {
    val renderItems = mutableListOf<CardRenderItem>()
    val visibleOrderedCards = cardOrder.filter { visibleCards.contains(it) }

    var i = 0
    while (i < visibleOrderedCards.size) {
        val currentCard = visibleOrderedCards[i]
        val pairGroup = currentCard.pairGroup
        val nextCard = visibleOrderedCards.getOrNull(i + 1)

        if (pairGroup != null && nextCard?.pairGroup == pairGroup) {
            renderItems.add(
                CardRenderItem(
                    cards = listOf(currentCard, nextCard),
                    isRow = true,
                ),
            )
            i += 2
            continue
        }

        renderItems.add(
            CardRenderItem(
                cards = listOf(currentCard),
                isRow = false,
            ),
        )
        i++
    }

    return renderItems
}

/**
 * Determine if a service-dependent card has data available to display.
 * This function is only relevant when the service is running.
 * Note: Profiles card is always available and should not use this function.
 */
fun isCardAvailableWhenServiceRunning(cardGroup: CardGroup, uiState: DashboardUiState): Boolean = when (cardGroup) {
    CardGroup.ClashMode -> uiState.clashModeVisible
    CardGroup.UploadTraffic -> uiState.trafficVisible
    CardGroup.DownloadTraffic -> uiState.trafficVisible
    CardGroup.Debug -> true // Debug info is always available when service is running
    CardGroup.Connections -> uiState.trafficVisible
    CardGroup.SystemProxy -> uiState.systemProxyVisible
    CardGroup.Profiles -> true // This shouldn't be called for Profiles, but return true for safety
}
