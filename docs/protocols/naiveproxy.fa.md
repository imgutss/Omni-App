<div dir="rtl">

# NaiveProxy · اتصال HTTPS با کلاینت Chromium

[English](naiveproxy.md) · [همهٔ راهنماها](README.md)

NaiveProxy در Omni از کلاینت واقعی Naive استفاده می‌کند. حالت پشتیبانی‌شده **HTTPS / HTTP2** است؛ `naive+quic` و HTTP3 در این اتصال پشتیبانی نمی‌شوند. یک سایت HTTPS یا Caddy معمولی به‌تنهایی کافی نیست؛ سرور باید افزونهٔ forwardproxy سازگار با Naive را داشته باشد.

## ۱ · سرور را آماده کن

به VPS لینوکس، یک دامنه مثل `naive.example.com`، Docker و Compose v2 نیاز داری. رکورد دامنه باید مستقیم به VPS اشاره کند. پورت‌های **80/TCP** و **443/TCP** باید آزاد و در فایروال سرور و پنل ارائه‌دهنده مجاز باشند؛ دسترسی SSH را نگه دار.

این چهار فایل را در یک پوشه بگیر و قبل از اجرا بخوان:

</div>

```sh
mkdir -p ~/omni-naive && cd ~/omni-naive
BASE=https://raw.githubusercontent.com/imgutss/Omni-App/main/docs/protocols/examples/naive
for file in Dockerfile compose.yaml Caddyfile index.html; do
  curl -fL "$BASE/$file" -o "$file" || exit 1
done
```

<div dir="rtl">

فایل Dockerfile، Caddy را با ماژول forwardproxy مخصوص Naive می‌سازد؛ همان روشی که [مستندات NaiveProxy](https://github.com/klzgrad/naiveproxy#server-setup) معرفی می‌کند.

## ۲ · دامنه و رمز خودت را بگذار

در `Caddyfile` این موارد را عوض کن:

| مقدار نمونه | جایگزین |
| :--- | :--- |
| `you@example.com` | ایمیل خودت برای صدور گواهی |
| `naive.example.com` | دامنهٔ واقعی سرور |
| `omni` | نام کاربری دلخواه پراکسی |
| `REPLACE_WITH_A_LONG_RANDOM_PASSWORD` | رمز تصادفی قوی؛ مثلاً خروجی `openssl rand -hex 32` |

بعد فایل را محدود، تصویر را بساز و تنظیمات را بررسی کن:

</div>

```sh
chmod 600 Caddyfile
docker compose build
docker compose run --rm --no-deps naive caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker compose up -d
docker compose logs --tail=80 naive
```

<div dir="rtl">

Caddy در صورت درست‌بودن DNS و دسترسی پورت‌ها، گواهی معتبر را خودکار می‌گیرد. حجم `caddy_data` محل وضعیت گواهی‌هاست؛ آن را بی‌دلیل پاک نکن. برای استقرار تکرارپذیر، شناسهٔ تصویر ساخته‌شده را ثبت کن؛ ساخت دوباره با نسخهٔ جدید ابزارها می‌تواند خروجی را تغییر دهد.

این نمونه برای مراجعهٔ عادی صفحهٔ خوش‌آمد می‌دهد و برای پراکسی احراز هویت می‌خواهد. HTTP3 عمداً خاموش است تا Caddy پورت UDP/443 را هم اشغال نکند.

## ۳ · پروفایل را در Omni بساز

از **+ → Add [NaiveProxy]** وارد شو:

| فیلد | مقدار |
| :--- | :--- |
| Remarks | نام دلخواه |
| Address | دامنهٔ واقعی، مثلاً `naive.example.com` |
| Port | `443` |
| Username / Password | دقیقاً مقادیر Caddyfile |
| SNI | همان دامنهٔ دارای گواهی |

ذخیره کن، پروفایل را انتخاب کن و وصل شو. اگر Address را IP می‌گذاری، SNI همچنان باید نام دامنهٔ گواهی باشد. اعتبارسنجی گواهی را دور نزن.

قالب لینک زیر فقط نمونه است؛ داده‌های واقعی خودت را جایگزین کن. کاراکترهای ویژه باید percent-encode شوند؛ Share داخل برنامه این کار را انجام می‌دهد.

</div>

```text
naive+https://USERNAME:PASSWORD@naive.example.com:443?sni=naive.example.com#My-Naive
```

<div dir="rtl">

## ۴ · اگر وصل نشد

- وضعیت سرویس و لاگ را با `docker compose ps` و `docker compose logs --tail=80 naive` ببین.
- `curl -I https://naive.example.com` فقط سلامت مسیر سایت و گواهی را می‌سنجد، نه کارکرد پراکسی با نام کاربری و رمز.
- خطای احراز هویت: نام کاربری و رمز اپ را با Caddyfile تطبیق بده.
- خطای گواهی: DNS، SNI، ساعت دستگاه و صدور گواهی را اصلاح کن؛ راه‌حل، خاموش‌کردن بررسی گواهی نیست.
- خطای bind: سرویس دیگری پورت 80 یا 443 را گرفته است؛ دو راهنما را بدون برنامه‌ریزی پورت‌ها روی هم اجرا نکن.

این مسیر، پراکسی TCP است؛ از وصل‌شدن آن نمی‌شود حمل UDP یا کاهش پینگ بازی را نتیجه گرفت. فایل تنظیمات و لینک پروفایل را خصوصی نگه دار و گواهی‌ها را همراه حجم دادهٔ Caddy حفظ کن.

[فایل‌های آمادهٔ نمونه](examples/naive/) · [پروژهٔ NaiveProxy](https://github.com/klzgrad/naiveproxy)

</div>
