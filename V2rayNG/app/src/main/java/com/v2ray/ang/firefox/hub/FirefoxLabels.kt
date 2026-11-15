package com.v2ray.ang.firefox.hub

/**
 * The Firefox Hub's words, in Persian. The engine reports stable ids and codes (a doctor step id, a
 * failure code, an account status); this object turns each into the sentence the person reads. Keeping
 * the mapping in one place means the UI never shows a raw English stack trace or an unknown code.
 */
object FirefoxLabels {

    // ---- Connection Doctor steps ----

    fun doctorStepTitle(id: String): String = when (id) {
        "engine" -> "موتور فایرفاکس"
        "account" -> "ورود به حساب"
        "entitlement" -> "فعال‌سازی Guardian"
        "quota" -> "سهمیهٔ ماهانه"
        "pass" -> "گذر اتصال (ProxyPass)"
        "serverlist" -> "فهرست کشورها"
        "dns" -> "DNS و نشت آن"
        "tunnel" -> "تونل H2 CONNECT"
        "exit" -> "IP خروجی"
        "country" -> "کشور خروجی"
        "transport" -> "ترنسپورت"
        else -> id
    }

    fun doctorStatusWord(status: String): String = when (status) {
        "ok" -> "سالم"
        "fail" -> "ناموفق"
        "warn" -> "هشدار"
        "pending" -> "در انتظار"
        "skip" -> "رد شد"
        else -> status
    }

    fun doctorRecommendation(code: String): String = when (code) {
        "connect_network" -> "اینترنت گوشی وصل نیست؛ اول شبکه را وصل کن."
        "turn_on" -> "اتصال خاموش است؛ دکمهٔ اتصال را بزن."
        "sign_in" -> "با حساب Firefox خودت وارد شو."
        "account_unusable" -> "حسابی که الان قابل استفاده باشد نیست؛ سهمیه یا ورود را بررسی کن."
        "restart_engine" -> "موتور بالا نیامده؛ یک‌بار تلاش دوباره را بزن."
        "quota_exhausted" -> "سهمیهٔ این حساب تمام شده؛ حساب دیگری را فعال کن یا تا ریست صبر کن."
        "diagnose_network" -> "تونل ترافیک را عبور نداد؛ پزشک اتصال را با تست زنده اجرا کن."
        "dns_filtered" -> "شبکه DNS را دستکاری می‌کند؛ مسیر رمزنگاری‌شده و DNS اپراتور استفاده می‌شود."
        "exit_mismatch" -> "کشور خروجی با انتخابت فرق دارد؛ کشور دیگری را انتخاب کن."
        else -> ""
    }

    // ---- Account health ----

    enum class HealthTone { OK, WARN, ERROR }

    data class AccountHealth(val label: String, val tone: HealthTone)

    fun accountHealth(
        status: String,
        reasonCode: String,
        active: Boolean,
        unlimited: Boolean,
        limitBytes: Long,
        remainingBytes: Long,
    ): AccountHealth {
        // A spent or nearly-spent allowance is the first thing to say.
        if (!unlimited && limitBytes > 0 && remainingBytes in 0..0) {
            return AccountHealth("سهمیه تمام شده", HealthTone.ERROR)
        }
        return when {
            reasonCode == "quota_exhausted" -> AccountHealth("سهمیه تمام شده", HealthTone.ERROR)
            reasonCode == "quota_threshold" -> AccountHealth("نزدیک پایان سهمیه", HealthTone.WARN)
            status == "reauth_required" || reasonCode == "signin_required" ->
                AccountHealth("نیاز به ورود دوباره", HealthTone.ERROR)
            status == "unavailable" -> AccountHealth("موقتاً در دسترس نیست", HealthTone.WARN)
            status == "reserved" -> AccountHealth("رزرو برای بعد از ریست", HealthTone.WARN)
            active && status == "ready" -> AccountHealth("وارد شده و فعال", HealthTone.OK)
            status == "ready" -> AccountHealth("وارد شده، آماده", HealthTone.OK)
            else -> AccountHealth("نامشخص", HealthTone.WARN)
        }
    }

    // ---- Failure codes (proxy.Failure) ----

    fun failure(code: String): String = when (code) {
        "no_network" -> "اینترنت گوشی وصل نیست."
        "unreachable" -> "سرورهای موزیلا از این شبکه در دسترس نیستند."
        "inside_vpn" -> "ترافیک موتور دوباره به خود تونل برگشت؛ محافظت سوکت را بررسی کن."
        "upstream_proxy" -> "پراکسی بالادستی پاسخ نداد."
        "refused" -> "سرور اتصال را رد کرد."
        "location_refused" -> "این کشور برای این حساب رد شد؛ کشور دیگری را انتخاب کن."
        "sign_in" -> "ورود لازم است؛ دوباره با حساب Firefox وارد شو."
        "quota" -> "سهمیهٔ ماهانه تمام شده."
        "not_entitled" -> "این حساب سرویس VPN را فعال ندارد."
        "region" -> "موزیلا این منطقه را رد کرد؛ مسیر رله استفاده می‌شود."
        "server" -> "خطای سرور موزیلا."
        "no_account", "no_accounts" -> "حسابی وارد نشده است."
        "test_site" -> "سایت آزمایشی پاسخ نداد."
        "" -> ""
        else -> "خطای ناشناخته ($code)."
    }

    // ---- Network DNA ----

    fun networkDna(code: String): String = when (code) {
        "healthy" -> "شبکه سالم است؛ نام‌های موزیلا درست resolve می‌شوند."
        "dns_filtered" -> "این شبکه DNS را دستکاری می‌کند؛ مسیر رمزنگاری‌شده و DNS خود اپراتور جایگزین شده‌اند."
        "encrypted_dns_blocked_use_carrier" -> "DNS رمزنگاری‌شده روی این شبکه بسته است؛ از DNS خود اپراتور استفاده می‌شود."
        "bootstrap_blocked_use_relay" -> "هیچ مسیر DNS نام‌های موزیلا را resolve نکرد؛ رلهٔ پشتیبان وارد مدار می‌شود."
        else -> ""
    }

    // ---- Auto Best / misc ----

    fun selectionName(selection: String, name: String): String = when (selection) {
        "" -> "پیشنهادی (نزدیک‌ترین)"
        "fastest" -> "سریع‌ترین (انتخاب خودکار)"
        else -> name.ifBlank { selection.uppercase() }
    }
}
