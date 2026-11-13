package com.v2ray.ang.firefox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton

/**
 * The Firefox screen: a person signs in to **their own** Mozilla account on **this** phone, reads
 * **their own** allowance, and picks the account the connection spends.
 *
 * Two things are said plainly, because people are right to ask them:
 *
 * - the account stays here. The engine keeps it in this app's private storage, the screen never holds
 *   anything but what it shows, and nothing is sent anywhere except to Mozilla's own servers;
 * - this is not an official Mozilla app, and an unofficial client can put an account at risk.
 *
 * @param viewModel owns the engine calls and the state they produce.
 */
@Composable
fun FirefoxScreen(
    viewModel: FirefoxViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineReady = state.engine == FirefoxViewModel.Engine.READY

    Scaffold(
        topBar = { AppTopBar(title = "فایرفاکس", onBackClick = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "حساب فایرفاکس خودت",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "با حساب Firefox خودت، ماهی تا ۵۰ گیگ ترافیک داری. حساب، رمز و توکن‌ها فقط " +
                                "روی همین گوشی می‌مانند و فقط با سرورهای خودِ موزیلا حرف می‌زنند. " +
                                "هر کسی که این اپ را نصب می‌کند، فقط از سهمیهٔ حساب خودش استفاده می‌کند.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        when (state.engine) {
                            FirefoxViewModel.Engine.STARTING -> {
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        "موتور حساب در حال راه‌اندازی است…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            FirefoxViewModel.Engine.MISSING -> {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "موتور حساب روی این گوشی بالا نیامد؛ بدون آن ردیف «فایرفاکس» ترافیک " +
                                        "را عبور نمی‌دهد.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                if (state.problem.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        state.problem,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { viewModel.refresh() }, enabled = !state.busy) {
                                    Text("تلاش دوباره")
                                }
                            }

                            FirefoxViewModel.Engine.READY -> {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "موتور آماده است و روی پورت محلی ${state.port} گوش می‌دهد.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            state.signIn?.let { session ->
                item { SignInCard(session = session, busy = state.busy, viewModel = viewModel) }
            }

            if (state.signIn == null) {
                if (state.accounts.isEmpty()) {
                    item {
                        AddAccountCard(
                            enabled = engineReady && !state.busy,
                            onSubmit = { email, password -> viewModel.signIn(email, password) },
                        )
                    }
                } else {
                    items(state.accounts, key = { it.id }) { account ->
                        AccountCard(
                            account = account,
                            busy = state.busy,
                            onActivate = { viewModel.activate(account.id) },
                            onRefresh = { viewModel.refreshQuota(account.id) },
                            onRemove = { viewModel.remove(account.id) },
                        )
                    }
                    item {
                        AddAccountCard(
                            enabled = engineReady && !state.busy,
                            submitLabel = "افزودن حساب دیگرِ خودم",
                            title = null,
                            onSubmit = { email, password -> viewModel.signIn(email, password) },
                        )
                    }
                }
            }

            if (state.problem.isNotBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            state.problem,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("این حساب کجا می‌رود؟", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "هیچ‌جا. حساب، رمز و توکن‌ها فقط روی همین گوشی ذخیره می‌شوند و تنها با خودِ " +
                                "سرورهای موزیلا حرف می‌زنند. پنل Omni، ربات تلگرام و رله هیچ‌کدام به آن‌ها " +
                                "دسترسی ندارند و چیزی از این صفحه به آن‌ها فرستاده نمی‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "این اپ رسمی موزیلا نیست. استفاده از سرویس موزیلا با یک کلاینت غیررسمی ممکن " +
                                "است حساب را در معرض محدودیت بگذارد؛ سهمیه و شرایط سرویس را خود موزیلا تعیین " +
                                "می‌کند.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "برای اتصال: در لیست سرورها گروه «Firefox» را باز کن و ردیف «Firefox» را انتخاب " +
                                "کن، بعد دکمهٔ اتصال را بزن. ترافیک فقط از سهمیهٔ همین حساب می‌رود.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { NavigationBarsSpacer() }
        }
    }
}

@Composable
private fun AddAccountCard(
    enabled: Boolean,
    onSubmit: (email: String, password: String) -> Unit,
    submitLabel: String = "ورود و افزودن حساب",
    title: String? = "حساب دارم، وارد می‌شوم",
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("ایمیل حساب Firefox") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
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
            Button(onClick = { onSubmit(email, password) }, enabled = enabled) {
                Text(submitLabel)
            }
            if (title != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "حساب نداری؟ یک حساب Firefox رایگان در سایت خود موزیلا بساز و برگرد.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "موزیلا معمولاً بعد از رمز، یک کد به ایمیل یا یک تصویر امنیتی می‌خواهد؛ همان لحظه " +
                        "همین‌جا از تو پرسیده می‌شود. اگر ورود از این شبکه مسدود باشد، صفحه مسیر دیگری " +
                        "پیشنهاد می‌کند.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SignInCard(
    session: FirefoxEngineApi.SignIn,
    busy: Boolean,
    viewModel: FirefoxViewModel,
) {
    var answer by rememberSaveable(session.id) { mutableStateOf("") }
    val captchaBitmap = remember(session.captcha) {
        runCatching {
            val encoded = session.captcha.substringAfter(',', session.captcha)
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (session.needsAnswer) "موزیلا چیزی می‌خواهد" else "در حال ورود…",
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            if (session.message.isNotBlank()) {
                Text(session.message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
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
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text(if (session.captcha.isBlank()) "کد تأیید" else "پاسخ تصویر") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.answer(answer) }, enabled = !busy && answer.isNotBlank()) {
                        Text("فرستادن")
                    }
                    OutlinedButton(onClick = { viewModel.cancelSignIn() }, enabled = !busy) {
                        Text("انصراف")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { viewModel.cancelSignIn() }, enabled = !busy) {
                        Text("انصراف")
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: FirefoxEngineApi.Account,
    busy: Boolean,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        account.email.ifBlank { "حساب فایرفاکس" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        fontSize = 15.sp,
                    )
                    Text(
                        if (account.active) "حساب فعال اتصال" else account.status.ifBlank { "آماده" },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            QuotaSummary(account)
            if (account.reason.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    account.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!account.active) {
                    OutlinedButton(onClick = onActivate, enabled = !busy) { Text("انتخاب برای اتصال") }
                }
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("به‌روزرسانی سهمیه") }
                OutlinedButton(onClick = onRemove, enabled = !busy) { Text("حذف از این گوشی") }
            }
        }
    }
}

@Composable
private fun QuotaSummary(account: FirefoxEngineApi.Account) {
    if (account.unlimited) {
        Text("این حساب محدودیت حجم ندارد.", fontWeight = FontWeight.Medium)
        return
    }
    if (account.limitBytes <= 0) {
        Text("سهمیه تا حالا خوانده نشده. «به‌روزرسانی سهمیه» را بزن.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val usedGb = account.usedBytes.toDouble() / GB
    val limitGb = account.limitBytes.toDouble() / GB
    Text("%.1f از %.0f گیگ استفاده شده".format(usedGb, limitGb), fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { (account.usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
        modifier = Modifier.fillMaxWidth(),
    )
    if (account.resetAtMillis > 0L) {
        Spacer(Modifier.height(8.dp))
        Text("ریست: ${daysUntil(account.resetAtMillis)} روز دیگر", style = MaterialTheme.typography.bodySmall)
    }
}

private const val GB = 1024.0 * 1024.0 * 1024.0

private fun daysUntil(epochMillis: Long): Long {
    val left = epochMillis - System.currentTimeMillis()
    return if (left <= 0) 0 else (left / (24L * 60L * 60L * 1000L)) + if (left % (24L * 60L * 60L * 1000L) > 0) 1 else 0
}
