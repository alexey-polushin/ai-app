package me.lekseg.aiapp.mlcext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun MlcChatScreen(modifier: Modifier = Modifier) {
    val vm: MlcChatViewModel = viewModel()
    val phase by vm.bootstrapPhase
    val err by vm.errorMessage
    val download by vm.downloadDetail

    Column(modifier.fillMaxSize()) {
        when (phase) {
            BootstrapPhase.Idle, BootstrapPhase.Loading -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Загрузка конфигурации MLC…",
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            BootstrapPhase.Downloading -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Скачивание модели", style = MaterialTheme.typography.titleMedium)
                    Text(
                        download.phaseTitle,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        download.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val stepLabel =
                        if (download.stepsTotal > 0) {
                            "Шаг ${download.stepIndex} из ${download.stepsTotal}"
                        } else {
                            "Шаг ${download.stepIndex}"
                        }
                    Text(stepLabel, style = MaterialTheme.typography.labelLarge)
                    val filePart =
                        if (download.bytesTotal > 0L) {
                            (download.bytesRead.toDouble() / download.bytesTotal.toDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    val overall =
                        if (download.stepsTotal > 0) {
                            (
                                (download.stepIndex - 1).toFloat() + filePart.coerceIn(0f, 1f)
                                ) / download.stepsTotal.toFloat()
                        } else {
                            0f
                        }
                    Text(
                        "Общий прогресс",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (download.stepsTotal > 0) {
                        LinearProgressIndicator(
                            progress = { overall.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Текущий файл",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (download.bytesTotal > 0L) {
                        LinearProgressIndicator(
                            progress = { filePart },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val loaded =
                        formatDataSize(download.bytesRead) +
                            if (download.bytesTotal > 0L) {
                                " / ${formatDataSize(download.bytesTotal)}"
                            } else {
                                " (размер неизвестен, ждём завершения…)"
                            }
                    Text(loaded, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Скорость: ${formatSpeed(download.speedBytesPerSecond)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            BootstrapPhase.Failed -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Ошибка MLC", style = MaterialTheme.typography.titleMedium)
                    Text(
                        err ?: "Неизвестная ошибка",
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { vm.retryBootstrap() },
                        modifier = Modifier.padding(top = 16.dp),
                    ) { Text("Повторить") }
                }
            }
            BootstrapPhase.Ready -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 1.dp,
                    ) {
                        Text(
                            text = "Чат",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    MlcChatReadyPanel(vm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MlcStreamingAssistantRow(vm: MlcChatViewModel) {
    val streaming by vm.assistantStreaming
    Text(
        "Ассистент: $streaming",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MlcChatReadyPanel(vm: MlcChatViewModel, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val generating by vm.isGenerating
    val inputBarShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = vm.chatMessages,
                key = { index, _ -> index },
            ) { _, line ->
                when (line) {
                    is ChatLine.User -> Text(
                        "Вы: ${line.text}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    is ChatLine.Assistant -> Text(
                        "Ассистент: ${line.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (generating) {
                item(key = "mlc_streaming") {
                    MlcStreamingAssistantRow(vm)
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = inputBarShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…") },
                    enabled = !generating,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )
                if (generating) {
                    FilledTonalIconButton(
                        onClick = { vm.stopGenerating() },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Остановить ответ",
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            vm.sendUserMessage(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                        )
                    }
                }
            }
        }
    }
}
