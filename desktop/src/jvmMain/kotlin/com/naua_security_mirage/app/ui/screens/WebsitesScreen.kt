package com.naua_security_mirage.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naua_security_mirage.app.data.model.CustomWebsite
import com.naua_security_mirage.app.data.repository.SettingsRepository
import com.naua_security_mirage.app.ui.components.MirageCard
import com.naua_security_mirage.app.ui.components.MirageChip
import com.naua_security_mirage.app.ui.components.MirageHeader
import com.naua_security_mirage.app.ui.components.MirageSwitch
import com.naua_security_mirage.app.ui.theme.LocalMirageColors

@Composable
fun WebsitesScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val colors = LocalMirageColors.current
    var websites by remember { mutableStateOf(settingsRepository.getCustomWebsites()) }
    var newDomainInput by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) } // 0: All, 1: Active, 2: Disabled

    val filteredWebsites = websites.filter {
        when (filterMode) {
            1 -> it.isEnabled
            2 -> !it.isEnabled
            else -> true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        MirageHeader(
            title = "Обход сайтов",
            isSubScreen = true,
            onBackClick = onBack
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            // Input to add a new domain
            MirageCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = newDomainInput,
                        onValueChange = { newDomainInput = it },
                        placeholder = {
                            Text(
                                text = "Добавить домен (напр. rutracker.org)...",
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
                    IconButton(
                        onClick = {
                            val domain = newDomainInput.trim().lowercase()
                            if (domain.isNotEmpty()) {
                                if (settingsRepository.addCustomWebsite(domain)) {
                                    websites = settingsRepository.getCustomWebsites()
                                    newDomainInput = ""
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Добавить",
                            tint = colors.accent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips & Clear All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MirageChip(text = "Все (${websites.size})", selected = filterMode == 0, onClick = { filterMode = 0 })
                    MirageChip(text = "Включены", selected = filterMode == 1, onClick = { filterMode = 1 })
                    MirageChip(text = "Отключены", selected = filterMode == 2, onClick = { filterMode = 2 })
                }

                if (websites.isNotEmpty()) {
                    Text(
                        text = "Очистить все",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            settingsRepository.clearCustomWebsites()
                            websites = emptyList()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (websites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Список исключенных доменов пуст.\nДобавьте сайты, которые должны открываться напрямую.",
                    color = colors.inkMuted,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredWebsites, key = { it.domain }) { site ->
                    MirageCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = site.domain,
                                color = if (site.isEnabled) colors.ink else colors.inkMuted,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            MirageSwitch(
                                checked = site.isEnabled,
                                onCheckedChange = { isEnabled ->
                                    settingsRepository.setCustomWebsiteEnabled(site.domain, isEnabled)
                                    websites = settingsRepository.getCustomWebsites()
                                }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            IconButton(
                                onClick = {
                                    settingsRepository.removeCustomWebsite(site.domain)
                                    websites = settingsRepository.getCustomWebsites()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Удалить",
                                    tint = Color(0xFFEF4444).copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
