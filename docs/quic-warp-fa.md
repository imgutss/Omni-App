# راهنمای WARP، AmneziaWG، TUIC و ShadowQUIC در Omni

## وضعیت این اصلاحیه

این فایل راهنمای کدِ اصلاح‌شده است، نه تأیید انتشار APK جدید. موفقیت تست لینوکس به معنی تأیید روی گوشی اندرویدی یا شبکهٔ کاربر نیست.

## وارد کردن کانفیگ WARP Studio

متن کامل `[Interface]` و `[Peer]` را از WARP Studio کپی و از گزینهٔ Import from clipboard وارد کنید. کانفیگ دارای Jc/Jmin/Jmax/S1/S2/H1…H4 باید به‌صورت **AMNEZIAWG** شناسایی شود، نه WireGuard ساده. موارد زیر باید عیناً باقی بمانند:

- PrivateKey و PublicKey و Addressهای IPv4/IPv6
- Endpoint شامل IP و پورت
- MTU، DNS و PersistentKeepalive
- همهٔ پارامترهای AmneziaWG؛ مقدار صفر نباید با مقدار پیش‌فرض غیرصفر جایگزین شود

PrivateKey محرمانه است. اسکرین‌شات یا فایل حاوی آن را عمومی نکنید.

## اسکن IP و پورت

در ویرایشگر **AMNEZIAWG** یا **WIREGUARD**، برای peer عمومی Cloudflare دکمهٔ **Scan for a WARP endpoint** نمایش داده می‌شود و فعال است. قبل از اسکن VPN را متوقف کنید. اسکن از همان هستهٔ AETHER و پروتکل WireGuard استفاده می‌کند و پینگ HTTPS یا TCP را به‌عنوان سلامت UDP گزارش نمی‌کند.

پس از پیدا شدن endpoint، فقط address و port ویرایش می‌شوند؛ با تیک بالای صفحه ذخیره کنید. کلید، IP داخلی، MTU و پارامترهای AWG تغییر نمی‌کنند. اسکن از هویت WARP خود AETHER استفاده می‌کند؛ پیدا شدن endpoint دسترسی به Cloudflare را نشان می‌دهد، نه معتبر بودن کلید واردشده یا موفقیت قطعی همان تنظیمات AWG. برای سرور خصوصی WireGuard این اسکن کاربرد ندارد.

## TUIC v5

TUIC نیاز به **سرور TUIC v5 واقعی** دارد. کلید WARP یا فایل WireGuard را نمی‌توان به TUIC تبدیل کرد. UUID، رمز، آدرس، پورت و SNI را از مدیر سرور بگیرید.

قالب لینک:

```text
tuic://UUID:PASSWORD@HOST:PORT?sni=TLS_NAME&alpn=h3&congestion_control=bbr&udp_relay_mode=native&allow_insecure=0#NAME
```

نمونهٔ صرفاً آموزشی و غیرقابل اتصال:

```text
tuic://8f7a1c2e-0b3d-4e5f-9a6b-1c2d3e4f5a6b:CHANGE_ME@tuic.example.com:443?sni=tuic.example.com&alpn=h3&congestion_control=bbr&udp_relay_mode=native&allow_insecure=0#My-TUIC
```

لینک را از کلیپ‌بورد وارد کنید، یا در فرم TUIC مقادیر را دستی بنویسید. SNI باید با گواهی سرور مطابق باشد. بررسی گواهی را خاموش نکنید مگر آگاهانه برای تست. UDP باید در شبکه و فایروال سرور باز باشد.

## ShadowQUIC

به **سرور ShadowQUIC سازگار** نیاز دارد؛ سرور TUIC، Shadowsocks یا WARP جای آن را نمی‌گیرد. username، password و JLS server-name را از تنظیمات همان سرور بردارید.

قالب:

```text
shadowquic://USERNAME:PASSWORD@HOST:PORT?sni=JLS_SERVER_NAME&alpn=h3&congestion_control=bbr&over_stream=0#NAME
```

نمونهٔ صرفاً آموزشی و غیرقابل اتصال:

```text
shadowquic://CHANGE_USER:CHANGE_PASS@sq.example.com:443?sni=JLS_DOMAIN_FROM_SERVER&alpn=h3&congestion_control=bbr&over_stream=0#My-ShadowQUIC
```

SNI در اینجا باید دقیقاً همان server-name/JLS upstream تنظیم‌شده روی سرور باشد، نه لزوماً دامنهٔ اتصال. ALPN نیز باید با سرور مطابق باشد. `over_stream=0` خط مبنای UDP برای بازی است؛ کیفیت واقعی به مسیر شبکه بستگی دارد و تضمین پینگ نیست.

## رمز و IPv6 در لینک

کاراکترهای ویژهٔ username/password مانند `@`، `:`، `#` و `?` باید percent-encode شوند. نمونه: `p@ss` در لینک می‌شود `p%40ss`. از خروجی Share خود اپ استفاده کنید تا کدگذاری انجام شود. آدرس IPv6 را در لینک داخل کروشه بنویسید: `[2001:db8::1]:443`.

## اگر اتصال برقرار نشد

اصلاحیه برای آماده‌شدن ترافیک ۹۰ ثانیه فرصت می‌دهد و پس از آن به‌جای ماندن نامحدود روی Connecting، خطا می‌دهد. در لاگ اپ، `protect_requests` و `protect_failures` بدون نمایش کلید ثبت می‌شوند. خطای protect، بسته بودن UDP، کلید نامعتبر، DNS و مسدود بودن URL تست اتصال علت‌های متفاوت‌اند و باید جدا بررسی شوند. ردشدن URL تست لزوماً به معنی خراب بودن دست‌دهی پروتکل نیست.
