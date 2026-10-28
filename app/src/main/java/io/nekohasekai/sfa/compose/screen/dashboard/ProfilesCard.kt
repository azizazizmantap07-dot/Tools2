package io.nekohasekai.sfa.compose.screen.dashboard

import io.nekohasekai.sfa.compose.theme.GlassTextButton
import io.nekohasekai.sfa.compose.theme.GlassPopup
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileUpload
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import io.nekohasekai.sfa.compose.theme.GlassShapes
import io.nekohasekai.sfa.compose.theme.OnGlassPrimary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.GlassModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.qr.QRCodeDialog
import io.nekohasekai.sfa.compose.component.qr.QRSDialog
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.configuration.ProfileImportHandler
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.errorDialogBuilder
import io.nekohasekai.sfa.ktx.shareProfile
import io.nekohasekai.sfa.ktx.shareProfileAsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.nekohasekai.sfa.compose.theme.GlassAlertDialog
import io.nekohasekai.sfa.compose.theme.GlassDropdownMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesCard(
    profiles: List<Profile>,
    selectedProfileId: Long,
    isLoading: Boolean,
    showAddProfileSheet: Boolean,
    showProfilePickerSheet: Boolean,
    updatingProfileId: Long? = null,
    updatedProfileId: Long? = null,
    onProfileSelected: (Long) -> Unit,
    onProfileEdit: (Profile) -> Unit,
    onProfileDelete: (Profile) -> Unit,
    onProfileShare: (Profile) -> Unit,
    onProfileShareURL: (Profile) -> Unit,
    onProfileUpdate: (Profile) -> Unit,
    onProfileMove: (Int, Int) -> Unit,
    onShowAddProfileSheet: () -> Unit,
    onHideAddProfileSheet: () -> Unit,
    onShowProfilePickerSheet: () -> Unit,
    onHideProfilePickerSheet: () -> Unit,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val importHandler = remember { ProfileImportHandler(context) }

    var showQRCodeDialog by remember { mutableStateOf(false) }
    var qrCodeProfile by remember { mutableStateOf<Profile?>(null) }

    var showQRSDialog by remember { mutableStateOf(false) }
    var qrsProfile by remember { mutableStateOf<Profile?>(null) }
    var qrsProfileData by remember { mutableStateOf<ByteArray?>(null) }

    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingQrsData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    var showQRScanSheet by remember { mutableStateOf(false) }

    val importFromFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                coroutineScope.launch {
                    when (val parseResult = importHandler.parseUri(uri)) {
                        is ProfileImportHandler.UriParseResult.Success -> {
                            withContext(Dispatchers.Main) {
                                pendingImportName = parseResult.name
                                pendingImportUri = uri
                                showImportConfirmDialog = true
                            }
                        }
                        is ProfileImportHandler.UriParseResult.Error -> {
                            withContext(Dispatchers.Main) {
                                context.errorDialogBuilder(Exception(parseResult.message)).show()
                            }
                        }
                    }
                }
            }
        }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            val selectedProfile = profiles.find { it.id == selectedProfileId }
            if (selectedProfile != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val profileData = createProfileContent(selectedProfile)
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(profileData)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.success_profile_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.failed_save_profile, e.message),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    val saveJsonFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val selectedProfile = profiles.find { it.id == selectedProfileId }
            if (selectedProfile != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val jsonContent = File(selectedProfile.typed.path).readText()
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(jsonContent.toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.success_profile_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.failed_save_profile, e.message),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    val selectedProfile = profiles.find { it.id == selectedProfileId }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        elevated = true,
        glowColor = GlassGlowAccent,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.title_configuration),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                GlassCard(
                    onClick = onShowAddProfileSheet,
                    shape = GlassShapes.chip,
                    elevated = true,
                    glowColor = GlassGlowAccent,
                    modifier = Modifier.size(44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_profile),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ProfileSelectorButton(
                    selectedProfile = selectedProfile,
                    onClick = onShowProfilePickerSheet,
                )

                Spacer(modifier = Modifier.height(12.dp))

                ProfileInfoRow(profile = selectedProfile)

                Spacer(modifier = Modifier.height(16.dp))

                ProfileActionRow(
                    profile = selectedProfile,
                    isUpdating = selectedProfile?.id == updatingProfileId,
                    showUpdateSuccess = selectedProfile?.id == updatedProfileId,
                    onEdit = { selectedProfile?.let { onProfileEdit(it) } },
                    onUpdate = { selectedProfile?.let { onProfileUpdate(it) } },
                    onShareFile = {
                        selectedProfile?.let {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    context.shareProfile(it)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        }
                    },
                    onSaveFile = {
                        selectedProfile?.let {
                            saveFileLauncher.launch("${it.name}.bpf")
                        }
                    },
                    onSaveJson = {
                        selectedProfile?.let {
                            saveJsonFileLauncher.launch("${it.name}.json")
                        }
                    },
                    onShareJson = {
                        selectedProfile?.let {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    context.shareProfileAsJson(it)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        }
                    },
                    onShareURL = {
                        selectedProfile?.let {
                            qrCodeProfile = it
                            showQRCodeDialog = true
                        }
                    },
                    onShareQRS = {
                        selectedProfile?.let { profile ->
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val data = createProfileContent(profile)
                                    withContext(Dispatchers.Main) {
                                        qrsProfile = profile
                                        qrsProfileData = data
                                        showQRSDialog = true
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    if (showProfilePickerSheet) {
        ProfilePickerSheet(
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            onProfileSelected = { profile -> onProfileSelected(profile.id) },
            onProfileEdit = onProfileEdit,
            onProfileDelete = onProfileDelete,
            onProfileMove = onProfileMove,
            onDismiss = onHideProfilePickerSheet,
        )
    }

    if (showAddProfileSheet) {
        GlassModalBottomSheet(
            onDismissRequest = onHideAddProfileSheet,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_profile),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                )

                AddProfileOption(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.profile_add_import_file),
                    description = stringResource(R.string.import_from_file_description),
                    onClick = {
                        onHideAddProfileSheet()
                        importFromFileLauncher.launch("*/*")
                    },
                )

                AddProfileOption(
                    icon = Icons.Default.QrCodeScanner,
                    title = stringResource(R.string.profile_add_scan_qr_code),
                    description = stringResource(R.string.scan_qr_code_description),
                    onClick = {
                        onHideAddProfileSheet()
                        showQRScanSheet = true
                    },
                )

                AddProfileOption(
                    icon = Icons.Outlined.CreateNewFolder,
                    title = stringResource(R.string.profile_add_create_manually),
                    description = stringResource(R.string.create_new_profile_description),
                    onClick = {
                        onHideAddProfileSheet()
                        onOpenNewProfile(NewProfileArgs())
                    },
                )
            }
        }
    }

    if (showQRCodeDialog && qrCodeProfile != null) {
        val profile = qrCodeProfile!!
        val link = remember(profile) {
            Libbox.generateRemoteProfileImportLink(
                profile.name,
                profile.typed.remoteURL,
            )
        }
        val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
        val qrBitmap = QRCodeGenerator.rememberPrimaryBitmap(link, backgroundColor = surfaceColor)

        QRCodeDialog(
            bitmap = qrBitmap,
            onDismiss = {
                showQRCodeDialog = false
                qrCodeProfile = null
            },
        )
    }

    if (showQRSDialog && qrsProfile != null && qrsProfileData != null) {
        QRSDialog(
            profileData = qrsProfileData!!,
            profileName = qrsProfile!!.name,
            onDismiss = {
                showQRSDialog = false
                qrsProfile = null
                qrsProfileData = null
            },
        )
    }

    if (showImportConfirmDialog && pendingImportName != null) {
        GlassAlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportName = null
                pendingQrsData = null
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.import_profile_confirm_title)) },
            text = { Text(stringResource(R.string.import_profile_confirm_message, pendingImportName!!)) },
            confirmButton = {
                GlassTextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        val qrsData = pendingQrsData
                        val importUri = pendingImportUri
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                        coroutineScope.launch {
                            if (qrsData != null) {
                                when (val result = importHandler.importFromQRSData(qrsData)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onProfileEdit(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            } else if (importUri != null) {
                                when (val result = importHandler.importFromUri(importUri)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onProfileEdit(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                GlassTextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQRScanSheet) {
        QRScanSheet(
            onDismiss = { showQRScanSheet = false },
            onScanResult = { result ->
                showQRScanSheet = false
                when (result) {
                    is QRScanResult.QRSData -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRSData(result.data)) {
                                is ProfileImportHandler.QRSParseResult.Success -> {
                                    withContext(Dispatchers.Main) {
                                        pendingImportName = parseResult.name
                                        pendingQrsData = result.data
                                        showImportConfirmDialog = true
                                    }
                                }
                                is ProfileImportHandler.QRSParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                    is QRScanResult.RemoteProfile -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRCode(result.uri.toString())) {
                                is ProfileImportHandler.QRCodeParseResult.RemoteProfile -> {
                                    withContext(Dispatchers.Main) {
                                        onOpenNewProfile(
                                            NewProfileArgs(
                                                importName = parseResult.name,
                                                importUrl = parseResult.url,
                                            ),
                                        )
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.LocalProfile -> {
                                    when (val importResult = importHandler.importFromQRCode(result.uri.toString())) {
                                        is ProfileImportHandler.ImportResult.Success -> {
                                            withContext(Dispatchers.Main) {
                                                onProfileEdit(importResult.profile)
                                            }
                                        }
                                        is ProfileImportHandler.ImportResult.Error -> {
                                            withContext(Dispatchers.Main) {
                                                context.errorDialogBuilder(Exception(importResult.message)).show()
                                            }
                                        }
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

private suspend fun createProfileContent(profile: Profile): ByteArray {
    val content = ProfileContent()
    content.name = profile.name
    when (profile.typed.type) {
        TypedProfile.Type.Local -> {
            content.type = Libbox.ProfileTypeLocal
        }
        TypedProfile.Type.Remote -> {
            content.type = Libbox.ProfileTypeRemote
        }
    }
    content.config = java.io.File(profile.typed.path).readText()
    content.remotePath = profile.typed.remoteURL
    content.autoUpdate = profile.typed.autoUpdate
    content.autoUpdateInterval = profile.typed.autoUpdateInterval
    content.lastUpdated = profile.typed.lastUpdated.time
    return content.encode()
}

@Composable
private fun ProfileInfoRow(profile: Profile?) {
    if (profile == null) return

    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (profile.typed.type == TypedProfile.Type.Remote) {
                    Icons.Default.Cloud
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (profile.typed.type == TypedProfile.Type.Remote) {
                    stringResource(R.string.profile_type_remote)
                } else {
                    stringResource(R.string.profile_type_local)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (profile.typed.type == TypedProfile.Type.Remote) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = RelativeTimeFormatter.format(context, profile.typed.lastUpdated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProfileActionRow(
    profile: Profile?,
    isUpdating: Boolean,
    showUpdateSuccess: Boolean,
    onEdit: () -> Unit,
    onUpdate: () -> Unit,
    onShareFile: () -> Unit,
    onSaveFile: () -> Unit,
    onSaveJson: () -> Unit,
    onShareJson: () -> Unit,
    onShareURL: () -> Unit,
    onShareQRS: () -> Unit,
) {
    if (profile == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionButton(
            icon = Icons.Default.Edit,
            contentDescription = stringResource(R.string.edit),
            onClick = onEdit,
        )

        if (profile.typed.type == TypedProfile.Type.Remote) {
            ActionButton(
                icon = when {
                    showUpdateSuccess -> Icons.Default.Check
                    else -> Icons.Default.Refresh
                },
                contentDescription = stringResource(R.string.update_profile),
                onClick = onUpdate,
                enabled = !isUpdating && !showUpdateSuccess,
                isLoading = isUpdating,
            )
        }

        ShareButton(
            profile = profile,
            onShareFile = onShareFile,
            onSaveFile = onSaveFile,
            onSaveJson = onSaveJson,
            onShareJson = onShareJson,
            onShareURL = onShareURL,
            onShareQRS = onShareQRS,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    GlassCard(
        onClick = onClick,
        shape = GlassShapes.chip,
        elevated = true,
        glowColor = GlassGlowAccent,
        modifier = Modifier.size(44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ShareButton(
    profile: Profile,
    onShareFile: () -> Unit,
    onSaveFile: () -> Unit,
    onSaveJson: () -> Unit,
    onShareJson: () -> Unit,
    onShareURL: () -> Unit,
    onShareQRS: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ActionButton(
            icon = Icons.Default.IosShare,
            contentDescription = stringResource(R.string.menu_share),
            onClick = { expanded = true },
        )

        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_as_file)) },
                onClick = {
                    expanded = false
                    onSaveFile()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_file)) },
                onClick = {
                    expanded = false
                    onShareFile()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_content_json)) },
                onClick = {
                    expanded = false
                    onSaveJson()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DataObject,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_content_json)) },
                onClick = {
                    expanded = false
                    onShareJson()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DataObject,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            if (profile.typed.type == TypedProfile.Type.Remote) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_share_url)) },
                    onClick = {
                        expanded = false
                        onShareURL()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_qrs)) },
                onClick = {
                    expanded = false
                    onShareQRS()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}

/** One tappable glass tile of the "Add Profile" sheet (same tile style as the profile picker). */
@Composable
private fun AddProfileOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShapes.card,
        elevated = true,
        glowColor = GlassGlowAccent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnGlassPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
