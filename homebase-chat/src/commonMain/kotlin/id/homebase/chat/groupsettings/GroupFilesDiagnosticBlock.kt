package id.homebase.chat.groupsettings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.resources.MR
import id.homebase.resources.chat_group_files_diagnostic_absent
import id.homebase.resources.chat_group_files_diagnostic_db_admin_label
import id.homebase.resources.chat_group_files_diagnostic_db_group_label
import id.homebase.resources.chat_group_files_diagnostic_placeholder
import id.homebase.resources.chat_group_files_diagnostic_present
import id.homebase.resources.chat_group_files_diagnostic_server_admin_label
import id.homebase.resources.chat_group_files_diagnostic_server_group_label
import id.homebase.resources.chat_group_files_diagnostic_title
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only DB-vs-own-server diagnostic for the user's two group files
 * (main + admin), rendered at the very bottom of the group-info screen.
 *
 * Four rows: DB-group, DB-admin, Server-group, Server-admin. Each row
 * shows one of three states (Present / Placeholder / Absent for DB rows;
 * Present / Absent for Server rows — server never stores placeholders).
 *
 * Long-press copies a single-line support blob with full UUIDs to the
 * clipboard. Visible-row UUIDs are 8-hex truncated to fit on a phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupFilesDiagnosticBlock(
    diagnostic: GroupFilesDiagnostic,
    selfDomain: String?,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyBlob = buildSupportBlob(diagnostic, selfDomain)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    scope.launch {
                        clipboard.setClipEntry(clipEntryOf(copyBlob))
                    }
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(MR.string.chat_group_files_diagnostic_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))

        DiagnosticRow(
            label = stringResource(MR.string.chat_group_files_diagnostic_db_group_label),
            value = dbValueText(diagnostic.dbGroup, expectedUid = diagnostic.expectedMainUniqueId),
            isHealthy = diagnostic.dbGroup is DbFileRow.Present,
        )
        DiagnosticRow(
            label = stringResource(MR.string.chat_group_files_diagnostic_db_admin_label),
            value = dbValueText(diagnostic.dbAdmin, expectedUid = diagnostic.expectedAdminUniqueId),
            isHealthy = diagnostic.dbAdmin is DbFileRow.Present,
        )
        DiagnosticRow(
            label = stringResource(MR.string.chat_group_files_diagnostic_server_group_label),
            value = serverValueText(diagnostic.serverGroup, expectedUid = diagnostic.expectedMainUniqueId),
            isHealthy = diagnostic.serverGroup is ServerFileRow.Present,
        )
        DiagnosticRow(
            label = stringResource(MR.string.chat_group_files_diagnostic_server_admin_label),
            value = serverValueText(diagnostic.serverAdmin, expectedUid = diagnostic.expectedAdminUniqueId),
            isHealthy = diagnostic.serverAdmin is ServerFileRow.Present,
        )
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    isHealthy: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isHealthy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun dbValueText(row: DbFileRow, expectedUid: Uuid): String = when (row) {
    is DbFileRow.Present -> stringResource(
        MR.string.chat_group_files_diagnostic_present,
        shortHex(expectedUid),
        shortHex(row.versionTag),
        row.originalAuthor.domainName,
    )
    is DbFileRow.Placeholder -> stringResource(
        MR.string.chat_group_files_diagnostic_placeholder,
        shortHex(expectedUid),
    )
    DbFileRow.Absent -> stringResource(
        MR.string.chat_group_files_diagnostic_absent,
        shortHex(expectedUid),
    )
}

@Composable
private fun serverValueText(row: ServerFileRow, expectedUid: Uuid): String = when (row) {
    is ServerFileRow.Present -> stringResource(
        MR.string.chat_group_files_diagnostic_present,
        shortHex(expectedUid),
        shortHex(row.versionTag),
        row.originalAuthor.domainName,
    )
    ServerFileRow.Absent -> stringResource(
        MR.string.chat_group_files_diagnostic_absent,
        shortHex(expectedUid),
    )
}

/** First 8 hex chars of the UUID's canonical form. Stable, readable, and fits on a phone row. */
private fun shortHex(uuid: Uuid): String = uuid.toString().take(8)

/**
 * Single-line support blob with full UUIDs. Pasted by users into support
 * messages to give us everything we'd need to grep `homebase.log`.
 */
internal fun buildSupportBlob(d: GroupFilesDiagnostic, selfDomain: String?): String = buildString {
    append("[group-files] convo=").append(d.conversationId)
    append(" domain=").append(selfDomain ?: "?")
    append("\n  db-group=").append(describeDb(d.dbGroup))
    append("\n  db-admin=").append(describeDb(d.dbAdmin))
    append("\n  server-group=").append(describeServer(d.serverGroup))
    append("\n  server-admin=").append(describeServer(d.serverAdmin))
    append("\n  expectedMainUid=").append(d.expectedMainUniqueId)
    append(" expectedAdminUid=").append(d.expectedAdminUniqueId)
}

private fun describeDb(row: DbFileRow): String = when (row) {
    is DbFileRow.Present -> "Present(vt=${row.versionTag}, author=${row.originalAuthor.domainName}, fileId=${row.fileId})"
    is DbFileRow.Placeholder -> "Placeholder(fileId=${row.fileId})"
    DbFileRow.Absent -> "Absent"
}

private fun describeServer(row: ServerFileRow): String = when (row) {
    is ServerFileRow.Present -> "Present(vt=${row.versionTag}, author=${row.originalAuthor.domainName}, fileId=${row.fileId})"
    ServerFileRow.Absent -> "Absent"
}
