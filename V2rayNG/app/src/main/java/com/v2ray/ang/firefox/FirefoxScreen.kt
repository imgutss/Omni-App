package com.v2ray.ang.firefox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.OmniButton as Button
import com.v2ray.ang.ui.compose.OmniCard as Card
import com.v2ray.ang.ui.compose.OmniOutlinedButton as OutlinedButton
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Firefox screen: sign in to **your own** account on **this** phone, see **your own** allowance, and
 * choose the account the connection uses.
 *
 * The screen is deliberately explicit about two things people are right to ask about:
 *
 * - the account stays here. The copy says so, and the code backs it up: nothing is uploaded anywhere except
 *   to Mozilla itself;
 * - this is not an official Mozilla app. The copy says that too, and that an unofficial client can put an
 *   account at risk, so nobody is surprised later.
 *
 * @param engineAvailable whether this build can actually carry traffic on the selected row. When it cannot,
 *   the screen says so before anyone signs in, rather than failing at connect time.
 */
@Composable
fun FirefoxScreen(
    engineAvailable: Boolean,
    port: Int,
    onBack: () -> Unit,
) {
    var accounts by remember { mutableStateOf(FirefoxAccountStore.accounts()) }
    var signingIn by remember { mutableStateOf(false) }
    var readingQuotaFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(accounts.isEmpty()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refreshAccounts() {
        accounts = FirefoxAccountStore.accounts()
    }

    // The row the account path carries traffic on: created here, once, so the person opens the server list and
    // finds "Firefox" already there instead of importing anything. Its configuration is rewritten each time
    // this screen opens, so it always points at the port the engine really listens on.
    val context = LocalContext.current
    val engineState by produceState(
        initialValue = engineAvailable to port,
        key1 = engineAvailable,
        key2 = port,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { FirefoxBridge.ensureGroup() }
            FirefoxBridge.engine(context) to FirefoxBridge.port()
        }
    }

    fun readQuota(account: FirefoxAccount) {
        readingQuotaFor = account.id
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FirefoxSignIn.readQuota(account) }
            }
            readingQuotaFor = null
            result.fold(
                onSuccess = { quota ->
                    FirefoxAccountStore.saveQuota(account.id, quota)
                    refreshAccounts()
                },
                onFailure = { failure ->
                    val reason = (failure as? FirefoxError)?.message ?: failure.message.orEmpty()
                    FirefoxAccountStore.saveProblem(account.id, reason)
                    refreshAccounts()
                },
            )
        }
    }

    LaunchedEffect(accounts.map { it.id }) {
        // Read each account's own allowance once, so the card is never blank in a fresh install.
        accounts.filter { it.quotaReadAt == 0L && it.problem.isBlank() }.take(1).forEach { readQuota(it) }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Firefox", onBackClick = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 16.dp),
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
                            "با حساب Firefox خودت، ماهی تا ۵۰ گیگ ترافیک داری. حساب و رمز فقط روی همین گوشی " +
                                "می‌مانند و هیچ‌جای دیگری فرستاده نمی‌شوند. هر کسی که این اپ را نصب می‌کند، " +
                                "فقط از سهمیهٔ حساب خودش استفاده می‌کند.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!engineAvailable) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "این نسخه هنوز موتور اتصال فایرفاکس را همراه ندارد؛ پس ورود و دیدن سهمیه کار " +
                                    "می‌کند، اما ردیف «فایرفاکس» تا نسخهٔ بعدی ترافیک را عبور نمی‌دهد.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (accounts.isEmpty() && showForm) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("حساب دارم، وارد می‌شوم", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("ایمیل حساب Firefox") },
                                singleLine = true,
                                enabled = !signingIn,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("رمز") },
                                singleLine = true,
                                enabled = !signingIn,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (email.isBlank() || password.isBlank()) {
                                            error = "ایمیل و رمز را کامل کن."
                                            return@Button
                                        }
                                        error = ""
                                        signingIn = true
                                        scope.launch {
                                            val outcome = withContext(Dispatchers.IO) {
                                                runCatching { FirefoxSignIn.signIn(email, password) }
                                            }
                                            signingIn = false
                                            outcome.fold(
                                                onSuccess = { account ->
                                                    FirefoxAccountStore.save(account)
                                                    password = ""
                                                    showForm = false
                                                    refreshAccounts()
                                                    readQuota(FirefoxAccountStore.activeAccount() ?: account)
                                                },
                                                onFailure = { failure ->
                                                    error = when ((failure as? FirefoxError)?.kind) {
                                                        FirefoxError.Kind.CHALLENGE ->
                                                            "موزیلا یک بررسی امنیتی نشان داد. کمی بعد دوباره امتحان کن، " +
                                                                "یا وای‌فای/دیتا را عوض کن. اگر حسابت با گوگل یا اپل " +
                                                                "ساخته شده، باید با مرورگر وارد شوی."
                                                        FirefoxError.Kind.NETWORK ->
                                                            "اینترنت وصل نیست یا درخواست از گوشی بیرون نرفت."
                                                        FirefoxError.Kind.REGION ->
                                                            "سرویس ورود موزیلا از این شبکه در دسترس نیست."
                                                        else -> failure.message ?: "ورود انجام نشد."
                                                    }
                                                },
                                            )
                                        }
                                    },
                                    enabled = !signingIn,
                                ) {
                                    if (signingIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.size(8.dp))
                                    }
                                    Text("ورود و افزودن حساب")
                                }
                                OutlinedButton(onClick = { showForm = false }, enabled = !signingIn) {
                                    Text("بعداً")
                                }
                            }
                            if (error.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("حساب نداری؟", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "یک حساب Firefox رایگان بساز و برگرد. ساخت حساب در سایت خود موزیلا انجام می‌شود.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (accounts.isNotEmpty()) {
                items(accounts, key = { it.id }) { account ->
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
                                        if (account.active) "حساب فعال اتصال" else "آماده",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (readingQuotaFor == account.id) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            QuotaSummary(account)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "برای اتصال: در لیست سرورها گروه «Firefox» را باز کن و ردیف «Firefox» را انتخاب " +
                                    "کن، بعد دکمهٔ اتصال را بزن. ترافیک فقط از سهمیهٔ همین حساب می‌رود.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (engineState.first) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "موتور این گوشی روی پورت محلی ${engineState.second} آماده است.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!account.active) {
                                    OutlinedButton(onClick = {
                                        FirefoxAccountStore.setActive(account.id)
                                        refreshAccounts()
                                    }) { Text("انتخاب برای اتصال") }
                                }
                                OutlinedButton(onClick = { readQuota(account) }) { Text("به‌روزرسانی سهمیه") }
                                OutlinedButton(onClick = {
                                    FirefoxAccountStore.remove(account.id)
                                    refreshAccounts()
                                }) { Text("حذف از این گوشی") }
                            }
                            if (account.problem.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    account.problem,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedButton(onClick = { showForm = true; error = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("افزودن حساب دیگرِ خودم")
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
                                "برای اتصال، در لیست سرورها ردیف «فایرفاکس» را انتخاب کن" +
                                    if (engineAvailable) " (پورت محلی $port)." else ".",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item { NavigationBarsSpacer() }
        }
    }
}

@Composable
private fun QuotaSummary(account: FirefoxAccount) {
    if (account.unlimited) {
        Text("این حساب محدودیت حجم ندارد.", fontWeight = FontWeight.Medium)
        return
    }
    if (account.quotaLimitBytes <= 0) {
        Text("سهمیه تا حالا خوانده نشده. «به‌روزرسانی سهمیه» را بزن.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val usedGb = account.usedBytes.toDouble() / GB
    val limitGb = account.quotaLimitBytes.toDouble() / GB
    Text(
        "%.1f از %.0f گیگ استفاده شده".format(usedGb, limitGb),
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { account.usedFraction.toFloat() },
        modifier = Modifier.fillMaxWidth(),
    )
    if (account.quotaResetsAt > 0L) {
        Spacer(Modifier.height(8.dp))
        Text(
            "ریست: ${daysUntil(account.quotaResetsAt)} روز دیگر",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val GB = 1024.0 * 1024.0 * 1024.0

private fun daysUntil(epochMillis: Long): Long {
    val left = epochMillis - System.currentTimeMillis()
    return if (left <= 0) 0 else (left / (24L * 60L * 60L * 1000L)) + if (left % (24L * 60L * 60L * 1000L) > 0) 1 else 0
}
