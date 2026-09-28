package com.epicenter.hifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentGold
import com.epicenter.hifi.ui.theme.BorderDark
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextSecondary

enum class NavTab {
    LIBRARY, PLAYER, EPICENTER, EQUALIZER, SETTINGS
}

@Composable
fun BottomNavBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PureBlack)
            .navigationBarsPadding()
    ) {
        // Línea divisora sutil superior
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp)
                .background(BorderDark)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                icon = Icons.Default.LibraryMusic,
                label = "Biblioteca",
                selected = selectedTab == NavTab.LIBRARY,
                onClick = { onTabSelected(NavTab.LIBRARY) }
            )
            NavItem(
                icon = Icons.Default.PlayCircle,
                label = "Player",
                selected = selectedTab == NavTab.PLAYER,
                onClick = { onTabSelected(NavTab.PLAYER) }
            )
            NavItem(
                icon = Icons.Default.Tune,
                label = "Epicenter",
                selected = selectedTab == NavTab.EPICENTER,
                onClick = { onTabSelected(NavTab.EPICENTER) }
            )
            NavItem(
                icon = Icons.Default.GraphicEq,
                label = "EQ 31",
                selected = selectedTab == NavTab.EQUALIZER,
                onClick = { onTabSelected(NavTab.EQUALIZER) }
            )
            NavItem(
                icon = Icons.Default.Settings,
                label = "Ajustes",
                selected = selectedTab == NavTab.SETTINGS,
                onClick = { onTabSelected(NavTab.SETTINGS) }
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) AccentGold else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = if (selected) AccentGold else TextSecondary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
