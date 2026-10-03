package io.github.yuriyurin.fit3companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3Sleep
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun sleepDuration(millis: Long): String {
    val minutes = millis / 60000
    return if (AppStrings.locale.language == "ru") "${minutes / 60}ч ${minutes % 60}м"
        else "${minutes / 60}h ${minutes % 60}m"
}

@Composable
internal fun SleepSummaryCard(health: Fit3HealthCodec.State, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val episodes = Fit3Sleep.forDay(health.sleepEpisodes, LocalDate.now(), zone)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.titleLarge)
            Text(if (episodes.isEmpty()) "—" else sleepDuration(Fit3Sleep.durationMillis(episodes, health.sleepStages)),
                style = MaterialTheme.typography.headlineMedium)
            if (episodes.isEmpty()) Text(stringResource(R.string.sleep_empty), style = MaterialTheme.typography.bodySmall)
            else {
                val night = episodes.filter { Fit3Sleep.isNight(it, zone) }
                val day = episodes.filterNot { Fit3Sleep.isNight(it, zone) }
                Text(stringResource(R.string.sleep_split,
                    sleepDuration(Fit3Sleep.durationMillis(night, health.sleepStages)),
                    sleepDuration(Fit3Sleep.durationMillis(day, health.sleepStages))), style = MaterialTheme.typography.bodyMedium)
                if (episodes.any { !Fit3Sleep.completeStages(it, health.sleepStages) })
                    Text(stringResource(R.string.sleep_period_only), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.sleep_details), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun SleepDetails(health: Fit3HealthCodec.State) {
    val zone = ZoneId.systemDefault()
    var date by remember { mutableStateOf(LocalDate.now()) }
    val episodes = Fit3Sleep.forDay(health.sleepEpisodes, date, zone)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Text(date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", AppStrings.locale)))
            TextButton(onClick = { date = date.plusDays(1) }, enabled = date < LocalDate.now()) {
                Text("›", style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (episodes.isEmpty()) Text(stringResource(R.string.sleep_empty))
        else {
            Text(sleepDuration(Fit3Sleep.durationMillis(episodes, health.sleepStages)), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.sleep_total), color = MaterialTheme.colorScheme.onSurfaceVariant)
            episodes.forEach { SleepEpisodeCard(it, health.sleepStages, zone) }
        }
        Text(stringResource(R.string.sleep_day_rule), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SleepEpisodeCard(episode: Fit3Sleep.Episode, allStages: List<Fit3Sleep.Stage>, zone: ZoneId) {
    val stages = Fit3Sleep.stagesFor(episode, allStages)
    val complete = Fit3Sleep.completeStages(episode, allStages)
    val labels = listOf(R.string.sleep_awake, R.string.sleep_light, R.string.sleep_deep, R.string.sleep_rem)
        .map { stringResource(it) }
    val colors = listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
    val fallback = MaterialTheme.colorScheme.outlineVariant
    val format = DateTimeFormatter.ofPattern("dd MMM HH:mm", AppStrings.locale)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (Fit3Sleep.isNight(episode, zone)) R.string.sleep_night else R.string.sleep_day),
                style = MaterialTheme.typography.titleLarge)
            Text("${Instant.ofEpochMilli(episode.start).atZone(zone).format(format)} — ${Instant.ofEpochMilli(episode.end).atZone(zone).format(format)}")
            Text(stringResource(R.string.sleep_period, sleepDuration(episode.end - episode.start)))
            if (complete) Text(stringResource(R.string.sleep_actual,
                sleepDuration(Fit3Sleep.durationMillis(listOf(episode), allStages))))
            if (stages.isEmpty()) Text(stringResource(R.string.sleep_stages_missing),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text(stringResource(R.string.sleep_awakenings, Fit3Sleep.awakenings(episode, allStages)))
                // Unknown/missing spans remain blank, never invented or extended.
                Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                    val duration = (episode.end - episode.start).toFloat()
                    stages.forEach { stage ->
                        val index = stage.kind - Fit3Sleep.AWAKE
                        val x = (stage.start - episode.start) / duration * size.width
                        val width = (stage.end - stage.start) / duration * size.width
                        val row = when (stage.kind) { Fit3Sleep.AWAKE -> 0; Fit3Sleep.REM -> 1; Fit3Sleep.LIGHT -> 2; else -> 3 }
                        drawRect(colors.getOrElse(index) { fallback }, Offset(x, row * size.height / 4),
                            Size(width.coerceAtLeast(1f), size.height / 4 - 3.dp.toPx()))
                    }
                }
                labels.forEachIndexed { index, label ->
                    val kind = Fit3Sleep.AWAKE + index
                    val duration = Fit3Sleep.unionMillis(stages.filter { it.kind == kind }.map { it.start to it.end })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = colors[index])
                        Text(sleepDuration(duration))
                    }
                }
                if (stages.any { it.kind !in Fit3Sleep.AWAKE..Fit3Sleep.REM })
                    Text(stringResource(R.string.sleep_stage_unknown), style = MaterialTheme.typography.bodySmall)
                if (!complete) Text(stringResource(R.string.sleep_stages_partial), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
