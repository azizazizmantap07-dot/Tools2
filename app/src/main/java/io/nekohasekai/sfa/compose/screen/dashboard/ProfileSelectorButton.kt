package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.GlassCard
import io.nekohasekai.sfa.compose.theme.GlassGlowAccent
import io.nekohasekai.sfa.compose.theme.GlassShapes
import io.nekohasekai.sfa.compose.theme.OnGlassPrimary
import io.nekohasekai.sfa.compose.theme.OnGlassSecondary
import io.nekohasekai.sfa.compose.util.ProfileIcons
import io.nekohasekai.sfa.database.Profile

@Composable
fun ProfileSelectorButton(
    selectedProfile: Profile?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = GlassShapes.card,
        elevated = true,
        glowColor = GlassGlowAccent,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedProfile != null) {
                val profileIcon =
                    ProfileIcons.getIconById(selectedProfile.icon)
                        ?: Icons.AutoMirrored.Default.InsertDriveFile

                Icon(
                    imageVector = profileIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = OnGlassSecondary,
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = selectedProfile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = OnGlassPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.not_selected),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnGlassSecondary,
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.UnfoldMore,
                contentDescription = stringResource(R.string.expand),
                modifier = Modifier.size(20.dp),
                tint = OnGlassSecondary,
            )
        }
    }
}
