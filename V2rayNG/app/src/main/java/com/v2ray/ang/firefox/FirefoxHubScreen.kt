package com.v2ray.ang.firefox

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.firefox.hub.FirefoxLabels
import com.v2ray.ang.firefox.hub.FirefoxRecipes
import com.v2ray.ang.firefox.hub.QuotaMath
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import com.v2ray.ang.ui.compose.OmniSwitch as Switch
import com.v2ray.ang.ui.compose.OmniTextField as TextField
import com.v2ray.ang.ui.compose.OmniTextButton as TextButton

/**
 * The Firefox Hub — one place for the person's own Mozilla account path.
 *
 * Six tabs, each answering one question: am I connected (اتصال), what is happening right now
 * (کنترل), why is it not working (پزشک), where do I come out (مکان‌ها), whose allowance am I spending
 * (حساب‌ها), and how do I tune it (ابزارها). Everything the engine knows is shown in words, never as a
 * raw code, and nothing here holds an account's secrets.
 */
@Composable
fun FirefoxHubScreen(
    viewModel: FirefoxHubViewModel,
    onBack: () -> Unit,
    onConnect: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.firefox_hub_title), onBackClick = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HubTabs(selected = tab, onSelect = { tab = it })
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.problem.isNotBlank()) {
                    item(key = "problem") {
                        NoteCard(state.problem, MaterialTheme.colorScheme.error) { viewModel.clearMessage() }
                    }
                }
                if (state.message.isNotBlank()) {
                    item(key = "message") {
                        NoteCard(state.message, MaterialTheme.colorScheme.primary) { viewModel.clearMessage() }
                    }
                }
                when (tab) {
                    0 -> connectTab(state, viewModel, onConnect)
                    1 -> controlTab(state, viewModel)
                    2 -> doctorTab(state, viewModel)
                    3 -> locationsTab(state, viewModel)
                    4 -> accountsTab(state, viewModel)
                    else -> toolsTab(state, viewModel) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Firefox Blackbox")
                            putExtra(Intent.EXTRA_TEXT, viewModel.exportEvents())
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, "ارسال گزارش")) }
                    }
                }
                item(key = "nav_spacer") { NavigationBarsSpacer() }
            }
        }
    }
}

private val TAB_TITLES = listOf("اتصال", "کنترل", "پزشک", "مکان‌ها", "حساب‌ها", "ابزارها")

@Composable
private fun HubTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TAB_TITLES.forEachIndexed { index, title ->
            if (index == selected) {
                Button(onClick = { onSelect(index) }) { Text(title) }
            } else {
                OutlinedButton(onClick = { onSelect(index) }) { Text(title) }
            }
        }
    }
}

// ---------------------------------------------------------------- connect

private fun androidx.compose.foundation.lazy.LazyListScope.connectTab(
    state: FirefoxHubViewModel.UiState,
    viewModel: FirefoxHubViewModel,
    onConnect: () -> Unit,
) {
    item(key = "engine") { EngineCard(state, viewModel) }

    item(key = "power") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val live = state.live
                val connected = state.serviceRunning && live?.phase == "running"
                QuotaRing(
                    usedPercent = if (live == null) 0.0
                    else QuotaMath.usedPercent(live.quotaLimitBytes, live.quotaRemainingBytes),
                    level = if (live == null) QuotaMath.Level.UNKNOWN
                    else QuotaMath.level(live.quotaUnlimited, live.quotaLimitBytes, live.quotaRemainingBytes),
                    centerTitle = when {
                        live == null -> "—"
                        live.quotaUnlimited -> "∞"
                        live.quotaLimitBytes <= 0 -> "؟"
                        else -> "${QuotaMath.usedPercent(live.quotaLimitBytes, live.quotaRemainingBytes).toInt()}٪"
                    },
                    centerSubtitle = when {
                        live == null -> "سهمیه"
                        live.quotaUnlimited -> "بدون محدودیت"
                        else -> QuotaMath.formatBytes(live.quotaRemainingBytes.coerceAtLeast(0)) + " مانده"
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        !state.serviceRunning -> "اتصال خاموش است"
                        live == null -> "در حال خواندن وضعیت…"
                        live.sleeping -> "تونل در خواب سبک است"
                        live.phase == "running" -> "متصل"
                        live.phase == "waiting" -> "در انتظار شبکه"
                        live.phase == "error" -> "خطا در اتصال"
                        else -> "در حال راه‌اندازی"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (live != null && connected) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        FirefoxLabels.selectionName(live.selection, live.countryName.ifBlank { live.selectionName }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (state.serviceRunning) {
                    val ctx = LocalContext.current
                    OutlinedButton(
                        onClick = { LauncherManager.stopService(ctx) },
                        enabled = !state.busy,
                    ) { Text("قطع اتصال") }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = state.engine == FirefoxHubViewModel.Engine.READY && !state.busy,
                    ) { Text("اتصال با حساب من") }
                }
            }
        }
    }

    item(key = "tiles") {
        val live = state.live
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("دانلود", QuotaMath.formatRate(live?.downRate ?: 0), Modifier.weight(1f))
            MetricTile("آپلود", QuotaMath.formatRate(live?.upRate ?: 0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("امروز", QuotaMath.formatBytes(state.todayUsageBytes), Modifier.weight(1f))
            MetricTile(
                "تأخیر",
                if ((live?.latencyMs ?: 0) > 0) "${live?.latencyMs} ms" else "—",
                Modifier.weight(1f),
            )
        }
    }

    item(key = "active_account") {
        val account = state.accounts.firstOrNull { it.active } ?: state.accounts.firstOrNull()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("حسابی که خرج می‌شود", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (account == null) {
                    Text(
                        "هنوز حسابی وارد نشده. از تب «حساب‌ها» با حساب Firefox خودت وارد شو.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(account.email.ifBlank { "حساب فایرفاکس" }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    val health = FirefoxLabels.accountHealth(
                        status = account.status,
                        reasonCode = account.reasonCode,
                        active = account.active,
                        unlimited = account.unlimited,
                        limitBytes = account.limitBytes,
                        remainingBytes = account.remainingBytes,
                    )
                    Text(health.label, color = healthColor(health.tone), style = MaterialTheme.typography.bodySmall)
                    if (!account.unlimited && account.limitBytes > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (account.usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${QuotaMath.formatBytes(account.usedBytes)} از ${QuotaMath.formatBytes(account.limitBytes)}" +
                                if (account.resetAtMillis > 0)
                                    " • ریست تا ${QuotaMath.daysUntil(account.resetAtMillis, System.currentTimeMillis())} روز دیگر"
                                else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    item(key = "shortcuts") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("میان‌برها", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.runConnectionTest() }, enabled = !state.busy) {
                        Text("تست اتصال")
                    }
                    OutlinedButton(onClick = { viewModel.restartProxy() }, enabled = !state.busy) {
                        Text("شروع دوبارهٔ پراکسی")
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(state: FirefoxHubViewModel.UiState, viewModel: FirefoxHubViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("حساب فایرفاکس خودت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "با حساب Firefox خودت ماهی تا ۵۰ گیگ ترافیک داری. حساب، رمز و توکن‌ها فقط روی همین گوشی " +
                    "می‌مانند و تنها با سرورهای خود موزیلا حرف می‌زنند.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            when (state.engine) {
                FirefoxHubViewModel.Engine.STARTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("موتور در حال راه‌اندازی است…", style = MaterialTheme.typography.bodySmall)
                }

                FirefoxHubViewModel.Engine.READY -> Text(
                    "موتور آماده است (پورت محلی ${state.port}).",
                    style = MaterialTheme.typography.bodySmall,
                )

                FirefoxHubViewModel.Engine.MISSING -> {
                    Text(
                        "موتور روی این گوشی بالا نیامد؛ بدون آن این مسیر ترافیک را عبور نمی‌دهد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.refresh() }, enabled = !state.busy) { Text("تلاش دوباره") }
                }
            }
        }
    }
}

/** The allowance ring: how much of the month is spent, coloured by how close to the edge it is. */
@Composable
private fun QuotaRing(
    usedPercent: Double,
    level: QuotaMath.Level,
    centerTitle: String,
    centerSubtitle: String,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = when (level) {
        QuotaMath.Level.GREEN -> Color(0xFF2E9E5B)
        QuotaMath.Level.YELLOW -> Color(0xFFE0A32E)
        QuotaMath.Level.RED -> Color(0xFFD64545)
        QuotaMath.Level.UNLIMITED -> MaterialTheme.colorScheme.primary
        QuotaMath.Level.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val fraction = (usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (level != QuotaMath.Level.UNKNOWN) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = if (level == QuotaMath.Level.UNLIMITED) 360f else 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTitle, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                centerSubtitle,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun MetricTile(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun NoteCard(text: String, color: Color, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    }
}

@Composable
private fun healthColor(tone: FirefoxLabels.HealthTone): Color = when (tone) {
    FirefoxLabels.HealthTone.OK -> Color(0xFF2E9E5B)
    FirefoxLabels.HealthTone.WARN -> Color(0xFFE0A32E)
    FirefoxLabels.HealthTone.ERROR -> MaterialTheme.colorScheme.error
}

// ---------------------------------------------------------------- control

private fun androidx.compose.foundation.lazy.LazyListScope.controlTab(
    state: FirefoxHubViewModel.UiState,
    viewModel: FirefoxHubViewModel,
) {
    item(key = "live_rows") {
        val live = state.live
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("کنترل زنده", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                if (live == null) {
                    Text("موتور هنوز وضعیتی نفرستاده است.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LiveRow("وضعیت", phaseWord(live.phase))
                    LiveRow("کشور خروجی", live.countryName.ifBlank { live.countryCode }.ifBlank { "—" })
                    LiveRow("شهر", live.cityName.ifBlank { "—" })
                    LiveRow("IP خروجی", live.exitIpv4.ifBlank { "—" })
                    LiveRow("ترنسپورت", live.transport.ifBlank { "—" })
                    LiveRow("خواب سبک", if (live.sleeping) "بله" else "خیر")
                    LiveRow("حجم این اجرا", QuotaMath.formatBytes(live.upBytes + live.downBytes))
                    LiveRow("امروز", QuotaMath.formatBytes(state.todayUsageBytes))
                    if (live.exitMismatch) {
                        LiveRow("هشدار", "کشور خروجی با انتخابت فرق دارد")
                    }
                    if (live.noNetwork) LiveRow("شبکه", "اینترنت گوشی وصل نیست")
                    val failure = live.connectFailure.ifBlank { live.wakeError }
                    if (failure.isNotBlank()) {
                        LiveRow("آخرین خطا", FirefoxLabels.failure(failure).ifBlank { failure })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.restartProxy() }, enabled = !state.busy) {
                        Text("شروع دوبارهٔ پراکسی")
                    }
                    OutlinedButton(onClick = { viewModel.runConnectionTest() }, enabled = !state.busy) {
                        Text("تست تونل")
                    }
                }
            }
        }
    }

    item(key = "events_head") {
        Text("آخرین رویدادها", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
    if (state.events.isEmpty()) {
        item(key = "events_empty") {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "هنوز رویدادی ثبت نشده است.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        // No key: two events can share a timestamp and text, and a duplicate key would crash the list.
        items(state.events.reversed()) { line ->
            Card(Modifier.fillMaxWidth()) {
                Text(line, Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LiveRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun phaseWord(phase: String): String = when (phase) {
    "running" -> "در حال کار"
    "starting" -> "در حال راه‌اندازی"
    "waiting" -> "در انتظار"
    "error" -> "خطا"
    "off" -> "خاموش"
    else -> phase.ifBlank { "—" }
}

// ---------------------------------------------------------------- doctor

private fun androidx.compose.foundation.lazy.LazyListScope.doctorTab(
    state: FirefoxHubViewModel.UiState,
    viewModel: FirefoxHubViewModel,
) {
    item(key = "doctor_actions") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("پزشک اتصال", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "قدم‌به‌قدم بررسی می‌کند: موتور، حساب، سهمیه، DNS، تونل و کشور خروجی؛ و در پایان " +
                        "می‌گوید چه کار کنی.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.runDoctor(false) }, enabled = !state.doctorRunning) {
                        Text("بررسی سریع")
                    }
                    OutlinedButton(onClick = { viewModel.runDoctor(true) }, enabled = !state.doctorRunning) {
                        Text("بررسی با تست زنده")
                    }
                    if (state.doctorRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    state.doctor?.let { report ->
        item(key = "doctor_summary") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (report.ok) "همه‌چیز سالم است" else "یک مشکل پیدا شد",
                        fontWeight = FontWeight.SemiBold,
                        color = if (report.ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
                    )
                    val advice = FirefoxLabels.doctorRecommendation(report.recommendation)
                    if (advice.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(advice, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(report.steps, key = { it.id }) { step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            FirefoxLabels.doctorStepTitle(step.id),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            FirefoxLabels.doctorStatusWord(step.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                step.ok -> Color(0xFF2E9E5B)
                                step.failed -> MaterialTheme.colorScheme.error
                                step.warn -> Color(0xFFE0A32E)
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                    if (step.detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(step.detail, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    item(key = "dna") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("DNA شبکه", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val dna = state.dna
                if (dna == null) {
                    Text(
                        "هنوز بررسی نشده. این بخش می‌گوید روی این شبکه کدام مسیر DNS جواب می‌دهد.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        FirefoxLabels.networkDna(dna.recommendation).ifBlank { dna.recommendation },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    LiveRow("نام‌های resolve شده (رمزنگاری‌شده)", "${dna.bootstrapResolved} از ${dna.targets}")
                    LiveRow("نام‌های resolve شده (DNS اپراتور)", "${dna.systemResolved} از ${dna.targets}")
                    LiveRow("شبکه فیلتر است", if (dna.filtered) "بله" else "خیر")
                    LiveRow("رلهٔ پشتیبان", if (dna.sharedRelayEnabled) "روشن" else "خاموش")
                    LiveRow("بازگشت به DNS گوشی", if (dna.fallbackToSystem) "روشن" else "خاموش")
                    if (dna.systemServers.isNotEmpty()) {
                        LiveRow("DNSهای این شبکه", dna.systemServers.joinToString("، "))
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.refreshNetworkDna() }, enabled = !state.busy) {
                    Text("بررسی دوبارهٔ شبکه")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- locations

private fun androidx.compose.foundation.lazy.LazyListScope.locationsTab(
    state: FirefoxHubViewModel.UiState,
    viewModel: FirefoxHubViewModel,
) {
    item(key = "auto_best") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "بهترین کشور خودکار",
                    subtitle = "فقط وقتی اتصال قطع شود یا تأخیر خیلی بد شود کشور را عوض می‌کند؛ " +
                        "وسط دانلود و استریم هرگز جابه‌جا نمی‌شود.",
                    checked = state.autoBest,
                    onChange = { viewModel.setAutoBest(it) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.countryLock.isBlank()) "قفل کشور: ندارد"
                    else "قفل کشور: ${state.countryLock}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.countryLock.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { viewModel.setCountryLock("") }) { Text("برداشتن قفل") }
                }
                if (state.favorites.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("علاقه‌مندی‌ها: ${state.favorites.joinToString("، ")}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.pingAll() }, enabled = !state.pinging) { Text("سنجش همهٔ کشورها") }
                    if (state.pinging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (state.pingedAtMillis > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "آخرین سنجش: ${QuotaMath.resetCountdown(System.currentTimeMillis(), state.pingedAtMillis)} پیش",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    item(key = "loc_recommend") {
        LocationRow(
            title = "پیشنهادی (نزدیک‌ترین)",
            subtitle = "موزیلا نزدیک‌ترین کشور را انتخاب می‌کند",
            selected = state.live?.selection.isNullOrBlank(),
            favorite = false,
            locked = false,
            onSelect = { viewModel.selectLocation("") },
            onFavorite = null,
            onLock = null,
        )
    }
    item(key = "loc_fastest") {
        LocationRow(
            title = "سریع‌ترین (انتخاب خودکار)",
            subtitle = "هر بار سریع‌ترین کشورِ سنجش‌شده",
            selected = state.live?.selection == "fastest",
            favorite = false,
            locked = false,
            onSelect = { viewModel.selectLocation("fastest") },
            onFavorite = null,
            onLock = null,
        )
    }

    if (state.locations.isEmpty()) {
        item(key = "loc_empty") {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "فهرست کشورها هنوز خوانده نشده؛ موتور که بالا بیاید پر می‌شود.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        // The recommended pseudo-location ("REC") already has its own row above; don't repeat it
        // here as an English-labelled country card.
        items(state.locations.filterNot { it.recommended }, key = { it.code }) { location ->
            val code = location.code.uppercase()
            LocationRow(
                title = location.name.ifBlank { code },
                subtitle = buildString {
                    append(location.city.ifBlank { code })
                    if (location.ok) {
                        append(" • ${location.pingMs} ms")
                        if (location.jitterMs > 0) append(" ±${location.jitterMs}")
                        if (location.samples > 0) append(" • ${location.samples}/${location.attempts}")
                    } else if (location.error.isNotBlank()) {
                        append(" • ناموفق")
                    }
                },
                // Highlight the country the user chose (the saved selection), not the exit the
                // engine happens to be connected to. countryCode is empty while the tunnel is off
                // or when a country fails to connect, which made "انتخاب" look like a no-op.
                selected = state.live?.selection.equals(code, ignoreCase = true),
                favorite = code in state.favorites,
                locked = state.countryLock == code,
                onSelect = { viewModel.selectLocation(location.code) },
                onFavorite = { viewModel.toggleFavorite(code) },
                onLock = { viewModel.setCountryLock(if (state.countryLock == code) "" else code) },
            )
        }
    }
}

@Composable
private fun LocationRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    favorite: Boolean,
    locked: Boolean,
    onSelect: () -> Unit,
    onFavorite: (() -> Unit)?,
    onLock: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(title)
                            if (favorite) append(" ★")
                            if (locked) append(" 🔒")
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(subtitle, style = MaterialTheme.typography.labelSmall)
                }
                if (selected) {
                    Text("فعال", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = onSelect) { Text("انتخاب") }
                }
            }
            if (onFavorite != null || onLock != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onFavorite?.let { TextButton(onClick = it) { Text(if (favorite) "حذف از علاقه‌مندی" else "علاقه‌مندی") } }
                    onLock?.let { TextButton(onClick = it) { Text(if (locked) "برداشتن قفل" else "قفل این کشور") } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- accounts

private fun androidx.compose.foundation.lazy.LazyListScope.accountsTab(
    state: FirefoxHubViewModel.UiState,
    viewModel: FirefoxHubViewModel,
) {
    item(key = "quota_autopilot") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "خلبان خودکار سهمیه",
                    subtitle = "نزدیک پایان سهمیه خبر می‌دهد و در پایان، مسیر را به موتور دیگری می‌سپارد.",
                    checked = state.quotaAutopilot,
                    onChange = { viewModel.setQuotaAutopilot(it) },
                )
            }
        }
    }

    val session = state.signIn
    if (session != null) {
        item(key = "signin") { HubSignInCard(session, state.busy, viewModel) }
    } else {
        item(key = "add_account") {
            HubAddAccountCard(
                enabled = state.engine == FirefoxHubViewModel.Engine.READY && !state.busy,
                title = if (state.accounts.isEmpty()) "با حساب Firefox خودت وارد شو" else "افزودن حساب دیگرِ خودم",
                onSubmit = { email, password -> viewModel.signIn(email, password) },
            )
        }
    }

    items(state.accounts, key = { it.id }) { account ->
        HubAccountCard(
            account = account,
            busy = state.busy,
            onActivate = { viewModel.activate(account.id) },
            onRefresh = { viewModel.refreshQuota(account.id) },
            onRemove = { viewModel.remove(account.id) },
            onEnabled = { viewModel.setAccountEnabled(account.id, it) },
        )
    }

    item(key = "account_privacy") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("حساب من کجا می‌رود؟", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "هیچ‌جا. حساب، رمز و توکن‌ها فقط روی همین گوشی می‌مانند و تنها با سرورهای خود موزیلا " +
                        "حرف می‌زنند. پنل Omni، ربات و رله به آن‌ها دسترسی ندارند.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "این اپ رسمی موزیلا نیست؛ استفاده با کلاینت غیررسمی ممکن است حساب را در معرض محدودیت " +
                        "بگذارد. سهمیه و شرایط را خود موزیلا تعیین می‌کند.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HubAddAccountCard(enabled: Boolean, title: String, onSubmit: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            TextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("ایمیل حساب Firefox") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("رمز") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSubmit(email, password) }, enabled = enabled && email.isNotBlank() && password.isNotBlank()) {
                Text("ورود و افزودن حساب")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "موزیلا معمولاً بعد از رمز یک کد ایمیل یا تصویر امنیتی می‌خواهد؛ همان لحظه همین‌جا پرسیده می‌شود.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun HubSignInCard(session: FirefoxEngineApi.SignIn, busy: Boolean, viewModel: FirefoxHubViewModel) {
    var answer by rememberSaveable(session.id) { mutableStateOf("") }
    val captchaBitmap = remember(session.captcha) {
        runCatching {
            val encoded = session.captcha.substringAfter(',', session.captcha)
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(if (session.needsAnswer) "موزیلا چیزی می‌خواهد" else "در حال ورود…", fontWeight = FontWeight.SemiBold)
            if (session.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(session.message, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            if (session.needsAnswer) {
                if (captchaBitmap != null) {
                    Image(
                        bitmap = captchaBitmap.asImageBitmap(),
                        contentDescription = "تصویر امنیتی موزیلا",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                TextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text(if (session.captcha.isBlank()) "کد تأیید" else "پاسخ تصویر") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.answer(answer) }, enabled = !busy && answer.isNotBlank()) { Text("فرستادن") }
                    OutlinedButton(onClick = { viewModel.cancelSignIn() }, enabled = !busy) { Text("انصراف") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.cancelSignIn() }, enabled = !busy) { Text("انصراف") }
                }
            }
        }
    }
}

@Composable
private fun HubAccountCard(
    account: FirefoxEngineApi.Account,
    busy: Boolean,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(account.email.ifBlank { "حساب فایرفاکس" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val health = FirefoxLabels.accountHealth(
                        status = account.status,
                        reasonCode = account.reasonCode,
                        active = account.active,
                        unlimited = account.unlimited,
                        limitBytes = account.limitBytes,
                        remainingBytes = account.remainingBytes,
                    )
                    Text(health.label, style = MaterialTheme.typography.labelSmall, color = healthColor(health.tone))
                }
                Switch(checked = account.enabled, onCheckedChange = { onEnabled(it) })
            }
            Spacer(Modifier.height(10.dp))
            if (account.unlimited) {
                Text("این حساب محدودیت حجم ندارد.", style = MaterialTheme.typography.bodySmall)
            } else if (account.limitBytes <= 0) {
                Text("سهمیه خوانده نشده؛ «به‌روزرسانی سهمیه» را بزن.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "${QuotaMath.formatBytes(account.usedBytes)} از ${QuotaMath.formatBytes(account.limitBytes)} مصرف شده",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (account.usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (account.resetAtMillis > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ریست: ${QuotaMath.resetCountdown(account.resetAtMillis, System.currentTimeMillis())} دیگر",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (account.reason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(account.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!account.active) {
                    OutlinedButton(onClick = onActivate, enabled = !busy) { Text("فعال‌سازی") }
                }
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("به‌روزرسانی سهمیه") }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRemove, enabled = !busy) { Text("حذف از این گوشی") }
        }
    }
}

// ---------------------------------------------------------------- tools

private fun androidx.compose.foundation.lazy.LazyListScope.toolsTab(
    state: FirefoxHubViewModel.UiState,
    viewModel: FirefoxHubViewModel,
    onExport: () -> Unit,
) {
    item(key = "recipes_head") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("نسخه‌های آماده", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "با یک ضربه موتور را برای کاری که می‌کنی تنظیم می‌کند؛ لازم نیست معنی ترنسپورت و " +
                        "DNS را بدانی.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    // Prefix the keys: a recipe id ("privacy") must not collide with a static card key in this
    // same LazyColumn — a duplicate key throws and crashes the Tools tab.
    items(FirefoxRecipes.Recipe.entries.toList(), key = { "recipe_${it.id}" }) { recipe ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(FirefoxHubViewModel.recipeTitle(recipe), fontWeight = FontWeight.Medium)
                        Text(FirefoxHubViewModel.recipeSubtitle(recipe), style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.recipe == recipe.id) {
                        Text("فعال", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { viewModel.applyRecipe(recipe) }, enabled = !state.busy) { Text("اعمال") }
                    }
                }
            }
        }
    }

    item(key = "shields") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("محافظ‌ها", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "سپر ترکیبی (Hybrid Shield)",
                    subtitle = "اگر کل مسیر فایرفاکس از کار بیفتد، تونل را به موتور دیگری می‌دهد؛ " +
                        "هرگز مسیر مستقیم و بی‌محافظ نمی‌شود.",
                    checked = state.hybridShield,
                    onChange = { viewModel.setHybridShield(it) },
                )
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "نگهبان نشست (Session Guardian)",
                    subtitle = "اگر بالادستی گیر کند، پراکسی را درجا از نو راه می‌اندازد.",
                    checked = state.sessionGuardian,
                    onChange = { viewModel.setSessionGuardian(it) },
                )
            }
        }
    }

    item(key = "privacy") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("حسابرسی حریم خصوصی", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• حساب و توکن‌ها: فقط در حافظهٔ خصوصی همین اپ.\n" +
                        "• ثبت رویداد: فقط رویداد و دلیل؛ هیچ آدرس بازدید، رمز یا توکنی ثبت نمی‌شود.\n" +
                        "• DNS: مسیرهای رمزنگاری‌شده اول؛ پاسخ‌های دستکاری‌شدهٔ شبکه رد می‌شوند.\n" +
                        "• نشت: اگر تونل نخورد، اتصال قطع می‌شود، نه اینکه مستقیم برود.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    item(key = "blackbox") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("جعبهٔ سیاه", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "آخرین رویدادهای مسیر فایرفاکس را نگه می‌دارد تا مشکلی که در جیب اتفاق افتاده بعداً " +
                        "خوانده شود.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport) { Text("ارسال گزارش") }
                    OutlinedButton(onClick = { viewModel.clearEvents() }) { Text("پاک کردن") }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
