package com.epicenter.hifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.BorderDark
import com.epicenter.hifi.ui.theme.DarkBackground
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.nativeText

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
        NavTab.PLAYER to (Icons.Default.Home to nativeText("Inicio", "Home")),
        NavTab.LIBRARY to (Icons.Default.LibraryMusic to nativeText("Música", "Music")),
        NavTab.SEARCH to (Icons.Default.Search to nativeText("Buscar", "Search")),
        NavTab.EPICENTER to (Icons.Default.Tune to "Epicenter"),
        NavTab.EQUALIZER to (Icons.Default.GraphicEq to "EQ"),
        NavTab.EFFECTS to (Icons.Default.Waves to nativeText("Efectos", "Effects")),
        NavTab.SETTINGS to (Icons.Default.Settings to nativeText("Ajustes", "Settings"))
    )
    Column(
        modifier = modifier.fillMaxWidth().background(DarkBackground).navigationBarsPadding()
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(BorderDark))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { (tab, item) ->
                val selected = tab == selectedTab
                val active = when (tab) {
                    NavTab.EPICENTER -> epicenterEnabled
                    NavTab.EQUALIZER -> eqEnabled
                    NavTab.EFFECTS -> effectsEnabled
                    else -> false
                }
                NavItem(
                    icon = item.first,
                    label = item.second,
                    selected = selected,
                    active = active,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 1.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.TopEnd) {
            Icon(icon, contentDescription = label, tint = if (selected) AccentRed else TextSecondary, modifier = Modifier.size(19.dp))
            if (active) androidx.compose.foundation.layout.Box(
                Modifier.padding(top = 1.dp, end = 1.dp).size(5.dp).background(AccentRed, RoundedCornerShape(50))
            )
        }
        Text(
            label,
            color = if (selected) AccentRed else TextSecondary,
            fontSize = if (label.length > 7) 7.sp else 8.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
