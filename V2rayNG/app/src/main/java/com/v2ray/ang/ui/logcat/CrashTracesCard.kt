package com.v2ray.ang.ui.logcat

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.OmniCard as Card
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import com.v2ray.ang.util.CrashGuard

/**
 * What the app kept instead of disappearing.
 *
 * A failure no layer handled is written to this app's private storage by [CrashGuard], in the process
 * it happened in, and nothing is sent anywhere by itself. This card is how a person reads it, copies
 * it into a report of their own, or clears it once it is no longer needed.
 */
@Composable
fun CrashTracesCard(
    context: Context,
    traces: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (traces.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.title_crash_traces),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.summary_crash_traces, traces.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.crash_traces_hide else R.string.crash_traces_show
                        )
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                traces.forEachIndexed { index, trace ->
                    Text(
                        trace,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    )
                    if (index != traces.lastIndex) Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopy(traces.joinToString("\n\n")) }) {
                        Text(stringResource(R.string.crash_traces_copy))
                    }
                    OutlinedButton(onClick = onClear) {
                        Text(stringResource(R.string.crash_traces_clear))
                    }
                }
            }
        }
    }
}
