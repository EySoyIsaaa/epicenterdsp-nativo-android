package com.epicenter.hifi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.nativeText
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary

enum class NavTab {
    PLAYER, LIBRARY, SEARCH, EPICENTER, EQUALIZER, EFFECTS, SETTINGS
}

@Composable
fun BottomNavBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    epicenterEnabled: Boolean,
    eqEnabled: Boolean,
    effectsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        NavItemData(NavTab.PLAYER, Icons.Default.PlayArrow, nativeText("Inicio", "Home")),
        NavItemData(NavTab.LIBRARY, Icons.Default.LibraryMusic, nativeText("Música", "Music")),
        NavItemData(NavTab.EPICENTER, Icons.Default.GraphicEq, "Epicenter"),
        NavItemData(NavTab.EQUALIZER, Icons.Default.Tune, "EQ"),
        NavItemData(NavTab.EFFECTS, Icons.Default.Waves, nativeText("Efectos", "Effects"))
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .floatingGlassSurface(RoundedCornerShape(38.dp))
            .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { item ->
            val selected = item.tab == selectedTab
            val active = when (item.tab) {
                NavTab.EPICENTER -> epicenterEnabled
                NavTab.EQUALIZER -> eqEnabled
                NavTab.EFFECTS -> effectsEnabled
                else -> false
            }
            FloatingNavItem(
                item = item,
                selected = selected,
                showActiveDot = active && !selected,
                onClick = { onTabSelected(item.tab) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private data class NavItemData(
    val tab: NavTab,
    val icon: ImageVector,
    val label: String
)

@Composable
private fun FloatingNavItem(
    item: NavItemData,
    selected: Boolean,
    showActiveDot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor by animateColorAsState(
        targetValue = if (selected) AccentRed else TextPrimary,
        animationSpec = tween(180),
        label = "nav_icon_color"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) AccentRed else TextPrimary,
        animationSpec = tween(180),
        label = "nav_label_color"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(34.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            if (showActiveDot) {
                Box(
                    Modifier
                        .padding(top = 1.dp, end = 1.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AccentRed)
                )
            }
        }
        Text(
            text = item.label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
