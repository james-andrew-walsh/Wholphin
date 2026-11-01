package com.github.damontecres.wholphin.ui.detail.discover

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.RemoveRequestResult
import com.github.damontecres.wholphin.ui.components.ConfirmDialog

/**
 * Confirms removing a request, which starts on Cancel since it deletes what has been downloaded
 *
 * @param title the movie or series
 */
@Composable
fun RemoveRequestDialog(
    title: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.remove_request),
        body = stringResource(R.string.remove_request_confirm, title ?: stringResource(R.string.unknown)),
        onCancel = onCancel,
        onConfirm = onConfirm,
        focusCancel = true,
    )
}

/**
 * What to tell the user about how a removal ended
 */
@get:StringRes
internal val RemoveRequestResult.stringRes: Int
    get() =
        when (this) {
            RemoveRequestResult.Removed -> R.string.remove_request
            RemoveRequestResult.InLibrary -> R.string.remove_request_in_library
            RemoveRequestResult.CheckFailed -> R.string.remove_request_check_failed
            RemoveRequestResult.ServiceFailed -> R.string.remove_request_service_failed
            RemoveRequestResult.RecordFailed -> R.string.remove_request_record_failed
        }
