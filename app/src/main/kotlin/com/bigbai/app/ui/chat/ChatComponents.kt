package com.bigbai.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigbai.app.ui.theme.ItaGptSerif
import com.bigbai.app.ui.theme.LocalReduceMotion
import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.GenerationMetrics
import com.bigbai.core.model.Role
import com.bigbai.core.model.Source
import com.bigbai.core.repo.MemoryProposal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onSaveToMemory: (() -> Unit)? = null,
    onSaveFile: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == Role.USER
    // Every message bubble is pure white with black text and a serif face, in
    // light and dark mode alike. Only fenced code keeps a monospaced face.
    val bubbleColor = Color.White
    val bubbleText = Color.Black
    val bubbleMeta = Color(0xFF4A4A4A)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!isUser) {
                AssistantAvatar()
                Spacer(Modifier.width(8.dp))
            }
            Surface(
                color = bubbleColor,
                contentColor = bubbleText,
                border = BorderStroke(1.dp, Color(0xFFD7DCE6)),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 6.dp,
                    bottomEnd = if (isUser) 6.dp else 20.dp,
                ),
                modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                    message.attachments.forEach { attachment ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = bubbleMeta,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = bubbleMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    MarkdownText(message.content, color = bubbleText, fontFamily = ItaGptSerif)
                    if (message.sources.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        CompactSources(message.sources)
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp, start = if (isUser) 0.dp else 44.dp),
        ) {
            Text(
                text = formatTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isUser && message.tokensPerSecond != null) {
                Text(
                    text = " · %.1f tok/s".format(Locale.US, message.tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copia",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onSaveFile != null) {
                IconButton(onClick = onSaveFile, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.SaveAlt,
                        contentDescription = "Salva come file",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onShare != null) {
                IconButton(onClick = onShare, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = "Condividi",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onSaveToMemory != null && !isUser) {
                IconButton(onClick = onSaveToMemory, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = "Salva in memoria",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onEdit != null && isUser) {
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Modifica messaggio",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onRegenerate != null && !isUser) {
                IconButton(onClick = onRegenerate, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Rigenera risposta",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onSpeak != null && !isUser) {
                IconButton(onClick = onSpeak, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.VolumeUp,
                        contentDescription = "Leggi la risposta ad alta voce",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The only thing shown while the model works: the answer is not streamed, it
 * appears complete (see ChatViewModel), so the UI never shows half a sentence.
 */
@Composable
fun ThinkingBubble() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Spacer(Modifier.width(8.dp))
        Surface(
            color = Color.White,
            contentColor = Color.Black,
            border = BorderStroke(1.dp, Color(0xFFD7DCE6)),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp),
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                ThinkingIndicator()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Sto preparando la risposta completa…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4A4A4A),
                )
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "phase",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            // Each dot lags the previous one, so the row reads as a wave.
            val local = if (reduceMotion) 0.5f else ((phase + index * 0.22f) % 1f)
            val alpha = 0.25f + 0.75f * (1f - kotlin.math.abs(local * 2f - 1f))
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        RoundedCornerShape(50),
                    ),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Elaborazione in corso",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4A4A4A),
        )
    }
}

@Composable
fun MarkdownText(
    text: String,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
) {
    val blocks = remember(text) { splitFences(text) }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                if (block.isCode) {
                    CodeBlock(block.text, block.language)
                } else {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = block.text.trim(),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = fontFamily ?: ItaGptSerif,
                            ),
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

private data class Block(val text: String, val isCode: Boolean, val language: String = "")

private val languageTag = Regex("^[a-zA-Z0-9+#.-]{1,16}$")

private fun splitFences(text: String): List<Block> {
    val result = mutableListOf<Block>()
    val segments = text.split("```")
    segments.forEachIndexed { index, segment ->
        if (index % 2 == 1) {
            val firstLine = segment.substringBefore('\n').trim()
            val hasLanguage = languageTag.matches(firstLine)
            val code = if (hasLanguage) segment.substringAfter('\n', "") else segment
            result.add(
                Block(
                    text = code.trimEnd(),
                    isCode = true,
                    language = if (hasLanguage) firstLine.lowercase() else "",
                ),
            )
        } else if (segment.isNotBlank()) {
            result.add(Block(segment, false))
        }
    }
    return if (result.isEmpty()) listOf(Block(text, false)) else result
}

@Composable
private fun CodeBlock(code: String, language: String = "") {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = Color(0xFFF1F3F7),
        contentColor = Color(0xFF111111),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFD7DCE6)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.ifBlank { "codice" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2F4BDB),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copia codice",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF111111),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun CompactSources(sources: List<Source>) {
    val context = LocalContext.current
    Column {
        sources.take(3).forEachIndexed { index, source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                        }
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "[${index + 1}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun SourcesPanel(sources: List<Source>) {
    if (sources.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Fonti (${sources.size})",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                HorizontalDivider()
                sources.forEachIndexed { index, source ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                }
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = "[${index + 1}] ${source.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = source.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (source.snippet.isNotBlank()) {
                            Text(
                                text = source.snippet.take(220),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = "Le informazioni provengono da pagine web esterne: verifica i contenuti critici sulle fonti originali.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MetricsPanel(
    metrics: GenerationMetrics,
    modelLabel: String?,
    threads: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "%.1f tok/s · %.0f ms primo token".format(Locale.US, metrics.tokensPerSecond, metrics.firstTokenMs.toFloat()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                MetricRow("Token al secondo", "%.2f".format(Locale.US, metrics.tokensPerSecond))
                MetricRow("Token prompt", metrics.promptTokens.toString())
                MetricRow("Token generati", metrics.completionTokens.toString())
                MetricRow("Tempo al primo token", "${metrics.firstTokenMs} ms")
                MetricRow("Tempo di generazione", "%.1f s".format(Locale.US, metrics.generationMs / 1000.0))
                MetricRow("RAM usata dall'app", "%.0f MB".format(Locale.US, metrics.peakRamBytes / 1_048_576.0))
                MetricRow("Thread CPU", threads.toString())
                MetricRow("Modello", modelLabel ?: metrics.modelId)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MemoryProposalCard(
    proposals: List<MemoryProposal>,
    onSave: (MemoryProposal) -> Unit,
    onDismiss: () -> Unit,
) {
    if (proposals.isEmpty()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Salvare questi ricordi?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            proposals.forEach { proposal ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = proposal.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Categoria: ${proposal.categoryName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    TextButton(onClick = { onSave(proposal) }) { Text("Salva") }
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Ignora") }
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
