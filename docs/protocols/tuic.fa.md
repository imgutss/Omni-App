<div dir="rtl">

# TUIC v5 · مسیر QUIC برای TCP و UDP

[English](tuic.md) · [همهٔ راهنماها](README.md)

Omni کلاینت TUIC v5 را دارد؛ برای اتصال به سرور سازگار، گواهی TLS معتبر و مسیر **UDP قابل دسترس** نیاز داری. بازبودن یک سایت HTTPS به‌تنهایی نشان نمی‌دهد TUIC روی همان شبکه کار می‌کند.

## ۱ · پیش‌نیازها

VPS لینوکس با Docker و Compose v2، دامنه‌ای مانند `tuic.example.com` و پورت **443/UDP**. نمونه روی IPv4 گوش می‌دهد؛ رکورد A را به IP سرور بده و AAAA نامعتبر منتشر نکن. پورت **80/TCP** برای صدور و تمدید گواهی با روش standalone باید آزاد و قابل دسترس باشد. قوانین فایروال پنل ارائه‌دهنده هم مهم‌اند؛ دسترسی SSH را نگه دار.

</div>

```sh
mkdir -p ~/omni-tuic && cd ~/omni-tuic
BASE=https://raw.githubusercontent.com/imgutss/Omni-App/main/docs/protocols/examples/tuic
curl -fL "$BASE/compose.yaml" -o compose.yaml
curl -fL "$BASE/config.json" -o config.json
docker compose pull
```

<div dir="rtl">

این نمونه از تصویر رسمی sing-box استفاده می‌کند. بعد از دریافت، digest تصویر را ثبت و برای استقرار تکرارپذیر pin کن؛ برچسب `latest` ثابت نیست. قبل از ارتقای هسته، تنظیمات را دوباره بررسی کن.

## ۲ · گواهی معتبر بگیر

مقادیر DOMAIN و EMAIL را با داده‌های خودت عوض کن. اگر وب‌سرور دیگری پورت 80 را اشغال کرده، این روش هم‌زمان اجرا نمی‌شود؛ برای هم‌زیستی، روش صدور گواهی مناسب همان وب‌سرور را انتخاب کن.

</div>

```sh
DOMAIN=tuic.example.com
EMAIL=you@example.com
docker run --rm -it -p 80:80 \
  -v "$PWD/letsencrypt:/etc/letsencrypt" \
  certbot/certbot certonly --standalone --non-interactive --agree-tos \
  --email "$EMAIL" -d "$DOMAIN"
```

<div dir="rtl">

پوشهٔ `letsencrypt` حاوی کلید خصوصی گواهی است؛ آن را منتشر نکن. اگر گواهی معتبر و سامانهٔ تمدید داری، همان را با مسیر درست به سرویس معرفی کن.

## ۳ · کاربر سرور را بساز و سرویس را بالا بیاور

یک UUID و یک رمز تصادفی جدا بساز:

</div>

```sh
docker run --rm ghcr.io/sagernet/sing-box:latest generate uuid
openssl rand -hex 32
```

<div dir="rtl">

در `config.json`، UUID صفرِ نمونه و رمز جایگزین را عوض کن. **تمام** تکرارهای `tuic.example.com`، از جمله مسیر گواهی و کلید، باید دامنهٔ واقعی باشند. TLS روشن و `zero_rtt_handshake` در شروع خاموش بماند.

</div>

```sh
chmod 600 config.json
docker compose run --rm tuic check -c /etc/sing-box/config.json
docker compose up -d
docker compose logs --tail=80 tuic
```

<div dir="rtl">

نمونه از UDP/443، الگوریتم `bbr` و ALPN برابر `h3` استفاده می‌کند. سرویس QUIC یا HTTP3 دیگری نباید همان سوکت UDP را گرفته باشد.

## ۴ · پروفایل Omni را بساز

**+ → Add [TUIC v5]**:

| فیلد | مقدار |
| :--- | :--- |
| Address / Port | دامنه یا IP واقعی / `443` |
| UUID / Password | دقیقاً کاربر ساخته‌شده در سرور |
| SNI | دامنهٔ گواهی، مثلاً `tuic.example.com` |
| ALPN | `h3` |
| Allow insecure | **خاموش** |
| TUIC parameters | JSON زیر |

</div>

```json
{"congestion_control":"bbr","udp_relay_mode":"native","zero_rtt_handshake":false}
```

<div dir="rtl">

ذخیره کن و وصل شو. `udp_relay_mode` نوع رلهٔ UDP داخل پروتکل را تعیین می‌کند؛ نیاز خود TUIC به ارتباط UDP را حذف نمی‌کند.

قالب لینک زیر نمونه است؛ UUID، رمز و دامنه را جایگزین کن. برای کاراکترهای خاص، از Share برنامه استفاده کن تا escaping درست انجام شود.

</div>

```text
tuic://UUID:PASSWORD@tuic.example.com:443?sni=tuic.example.com&alpn=h3&congestion_control=bbr&udp_relay_mode=native&zero_rtt_handshake=0&allow_insecure=0#My-TUIC
```

<div dir="rtl">

## ۵ · عیب‌یابی و نگهداری

- با `sudo ss -lunp | grep ':443'` گوش‌دادن سرویس روی UDP را ببین؛ این فقط وضعیت محلی سرور است، نه اثبات دسترسی از گوشی.
- اگر TLS مشکل دارد، SNI، دامنهٔ گواهی، تاریخ انقضا و ساعت را بررسی کن؛ Allow insecure را راه‌حل اصلی نکن.
- اگر احراز هویت رد شد، UUID و رمز را تطبیق بده. تنظیمات TUIC v4 جای v5 نیست.
- اگر سرور UDP می‌شنود ولی گوشی وصل نمی‌شود، قوانین UDP فایروال سرور/پنل و محدودیت شبکه را بررسی کن؛ تست TCP کافی نیست.
- برای تمدید گواهی، همان حجم Certbot و روش مناسب سرورت را نگه دار؛ تمدید را زمان‌بندی و آزمایش کن و بعد از تمدید، سرویس را reload/restart کن. صدور یک‌باره، زمان‌بندی تمدید ایجاد نمی‌کند.
- 0-RTT را بدون شناخت خطر replay روشن نکن. اول اتصال پایه را درست کن، بعد سراغ بهینه‌سازی برو.

[تنظیمات رسمی سرور TUIC](https://sing-box.sagernet.org/configuration/inbound/tuic/) · [فایل‌های آمادهٔ نمونه](examples/tuic/)

</div>
