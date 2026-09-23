package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.core.AppScannerService
import com.naua_security_mirage.app.data.model.AppInfo
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.components.MirageCard
import com.naua_security_mirage.app.ui.components.MirageChip
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.components.MirageSwitch
import com.naua_security_mirage.app.ui.theme.LocalMirageColors
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val colors = LocalMirageColors.current
    val scope = rememberCoroutineScope()
    val scanner = remember { AppScannerService() }

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) } // 0: All, 1: Proxied, 2: Bypassed

    LaunchedEffect(Unit) {
        isLoading = true
        val scanned = scanner.scanInstalledApps()
        apps = scanned.map {
            it.copy(isProxied = settingsRepository.isAppProxied(it.packageName))
        }
        isLoading = false
    }

    val filteredApps = apps.filter { app ->
        val matchesSearch = searchQuery.isEmpty() ||
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (filterMode) {
            1 -> app.isProxied
            2 -> !app.isProxied
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        MirageHeader(
            title = "Приложения",
            isSubScreen = true,
            onBackClick = onBack
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            // Search Bar
            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Поиск установленных программ...",
                                color = colors.inkMuted,
                                fontSize = 13.sp
                            )
                        },
                        colors = TextFieldDefaults.textFieldColors(
                            backgroundColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            textColor = colors.ink,
                            cursorColor = colors.accent
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Очистить",
                                tint = colors.inkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips & Bulk Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MirageChip(text = "Все", selected = filterMode == 0, onClick = { filterMode = 0 })
                    MirageChip(text = "VPN", selected = filterMode == 1, onClick = { filterMode = 1 })
                    MirageChip(text = "В обход", selected = filterMode == 2, onClick = { filterMode = 2 })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Все VPN",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            settingsRepository.setAllAppsProxied(true, apps.map { it.packageName })
                            apps = apps.map { it.copy(isProxied = true) }
                        }
                    )
                    Text(
                        text = "•",
                        color = colors.inkMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Снять все",
                        color = colors.inkMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            settingsRepository.setAllAppsProxied(false, apps.map { it.packageName })
                            apps = apps.map { it.copy(isProxied = false) }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    MirageCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    color = colors.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = app.packageName,
                                    color = colors.inkMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            MirageSwitch(
                                checked = app.isProxied,
                                onCheckedChange = { isProxied ->
                                    settingsRepository.setAppProxied(app.packageName, isProxied)
                                    apps = apps.map {
                                        if (it.packageName == app.packageName) it.copy(isProxied = isProxied) else it
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
