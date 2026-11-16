package com.github.damontecres.wholphin.ui.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.QueueNotice
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.TextButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val QUEUE_LINE_HEIGHT = 48.dp

/**
 * What a notice says happened
 *
 * @param removedBy who to name in [reason], null if it names nobody
 * @param partial only the missing episodes of a series were stopped, which goes in front
 */
data class NoticeText(
    @param:StringRes val reason: Int,
    val removedBy: String? = null,
    val partial: Boolean = false,
)

internal fun noticeText(
    reason: String?,
    partial: Boolean,
    removedBy: String?,
): NoticeText =
    when (reason) {
        "not_found" -> NoticeText(R.string.queue_notice_not_found, partial = partial)
        "no_seeds" -> NoticeText(R.string.queue_notice_no_seeds, partial = partial)
        "stuck" -> NoticeText(R.string.queue_notice_stuck, partial = partial)
        "removed" -> {
            if (removedBy.isNullOrBlank()) {
                NoticeText(R.string.queue_notice_removed_unknown, partial = partial)
            } else {
                NoticeText(R.string.queue_notice_removed, removedBy, partial)
            }
        }

        else -> NoticeText(R.string.queue_notice_cancelled, partial = partial)
    }

/**
 * What happened to something removed, in words: "Removed by Alex", "Stuck, cancelled"
 */
@Composable
internal fun removalDescription(
    reason: String?,
    partial: Boolean,
    removedBy: String?,
): String {
    val text = noticeText(reason, partial, removedBy)
    val what = text.removedBy?.let { stringResource(text.reason, it) } ?: stringResource(text.reason)
    return if (text.partial) stringResource(R.string.queue_notice_partial, what) else what
}

@Composable
internal fun titleWithYear(
    title: String,
    year: Int?,
): String {
    val name = title.ifBlank { stringResource(R.string.unknown) }
    return year?.let { stringResource(R.string.queue_title_year, name, it) } ?: name
}

internal fun formatNoticeDate(epochSeconds: Long): String =
    DateTimeFormatter
        .ofPattern("MMM d", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/**
 * One of the user's notices, on one line with its OK button
 *
 * @param buttonModifier for the button, which is the only thing on the line that takes focus
 */
@Composable
internal fun QueueNoticeLine(
    notice: QueueNotice,
    acknowledging: Boolean,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    val what = removalDescription(notice.reason, notice.partial, notice.removedBy)
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(QUEUE_LINE_HEIGHT)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 16.dp),
    ) {
        Text(
            text = titleWithYear(notice.title, notice.year),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = what,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
        notice.at?.let {
            Text(
                text = formatNoticeDate(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Stays the same button while acknowledging so that it keeps focus
        TextButton(
            onClick = { if (!acknowledging) onAcknowledge.invoke() },
            modifier = buttonModifier,
        ) {
            if (acknowledging) {
                CircularProgress(Modifier.size(24.dp))
            } else {
                Text(text = stringResource(R.string.queue_notice_ok))
            }
        }
    }
}
