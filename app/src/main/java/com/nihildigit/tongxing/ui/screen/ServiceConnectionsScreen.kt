package com.nihildigit.tongxing.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nihildigit.tongxing.R
import com.nihildigit.tongxing.mcp.MCPServiceConfig
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.state.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceConnectionsScreen(preferences: AppPreferences, onBack: () -> Unit) {
    val running by AppState.overlayRunning.collectAsState()
    var urls by remember {
        mutableStateOf(MCPServiceConfig.PRESET_SERVICES.associate { service ->
            service.id to (preferences.mcpServiceUrls[service.id] ?: service.streamableHttpUrl.orEmpty())
        })
    }
    var reveal by remember { mutableStateOf(false) }
    var cloudKey by remember { mutableStateOf(preferences.cloudApiKey) }
    var saved by remember { mutableStateOf(false) }
    val valid = urls.values.all(MCPServiceConfig::isValidServiceUrl)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.service_connections)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.service_connections_help))
            if (running) Text(stringResource(R.string.stop_to_edit_services), color = MaterialTheme.colorScheme.primary)
            MCPServiceConfig.PRESET_SERVICES.forEach { service ->
                val label = when (service.id) {
                    "amap-maps" -> R.string.service_amap
                    "12306-mcp" -> R.string.service_trains
                    "variflight-mcp" -> R.string.service_flights
                    else -> R.string.service_taxi
                }
                val value = urls[service.id].orEmpty()
                val invalid = !MCPServiceConfig.isValidServiceUrl(value)
                OutlinedTextField(
                    value = value,
                    onValueChange = { urls = urls + (service.id to it); saved = false },
                    label = { Text(stringResource(label)) },
                    placeholder = { Text("https://…/mcp") },
                    enabled = !running,
                    isError = invalid,
                    supportingText = { if (invalid) Text(stringResource(R.string.https_url_required)) },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row {
                Checkbox(checked = reveal, onCheckedChange = { reveal = it })
                Text(stringResource(R.string.show_service_urls), modifier = Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(
                value = cloudKey,
                onValueChange = { cloudKey = it; saved = false },
                label = { Text(stringResource(R.string.cloud_api_key)) },
                supportingText = { Text(stringResource(R.string.cloud_vision_help)) },
                enabled = !running,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { preferences.mcpServiceUrls = urls; preferences.cloudApiKey = cloudKey.trim(); saved = true },
                enabled = valid && !running, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save))
            }
            if (saved) Text(stringResource(R.string.service_connections_saved))
        }
    }
}
