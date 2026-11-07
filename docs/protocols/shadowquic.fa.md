<div dir="rtl">

# ShadowQUIC · ارتباط QUIC با نام JLS هماهنگ

[English](shadowquic.md) · [همهٔ راهنماها](README.md)

ShadowQUIC سرور، کاربر و تنظیمات JLS خودش را می‌خواهد؛ کانفیگ TUIC یا WARP جای آن نیست. این راهنما از **v0.4.8**، هم‌نسخه با هستهٔ ShadowQUIC در Omni، استفاده می‌کند.

## ۱ · سرور و باینری

VPS لینوکس با systemd، دسترسی sudo و ابزارهای `curl` و `sha256sum` لازم است. نمونه روی **1443/UDP** گوش می‌دهد؛ این پورت را در فایروال سرور و پنل مجاز کن و SSH را نگه دار. خود سرور باید DNS و مقصد JLS، در این مثال `cloudflare.com:443`، را هم ببیند.

باینری رسمی static را متناسب با معماری بگیر و هش را بررسی کن:

</div>

```sh
mkdir -p ~/omni-shadowquic && cd ~/omni-shadowquic
case "$(uname -m)" in
  x86_64)
    ARCH=x86_64
    EXPECTED=f1bb3f0ee2db8a65582e08469f1a3c9420f1645eea2fd1d40d9a411e27ab6194 ;;
  aarch64|arm64)
    ARCH=aarch64
    EXPECTED=fe688fbf83cf3ea5a666f1079485102804c30e46ff2dab007e2fcefa06089ae9 ;;
  *) echo 'Use the official release for your architecture'; exit 1 ;;
esac
curl -fL --retry 3 \
  "https://github.com/spongebob888/shadowquic/releases/download/v0.4.8/shadowquic-${ARCH}-linux-musl" \
  -o shadowquic
printf '%s  shadowquic\n' "$EXPECTED" | sha256sum -c - || exit 1
sudo install -m 0755 shadowquic /usr/local/bin/shadowquic
```

<div dir="rtl">

با خطای هش ادامه نده. این هش‌ها مخصوص همین دو فایل نسخهٔ 0.4.8 هستند؛ فایل معماری یا نسخهٔ دیگر، هش خودش را لازم دارد.

## ۲ · تنظیمات خودت را بساز

</div>

```sh
BASE=https://raw.githubusercontent.com/imgutss/Omni-App/main/docs/protocols/examples/shadowquic
curl -fL "$BASE/server.yaml" -o server.yaml
curl -fL "$BASE/omni-shadowquic.service" -o omni-shadowquic.service
openssl rand -hex 32
```

<div dir="rtl">

در `server.yaml` نام کاربری و رمز جایگزین را عوض کن. نام `cloudflare.com` در `jls-upstream.addr` را می‌توانی نگه داری یا یک مقصد JLS قابل دسترس و سازگار انتخاب کنی؛ نام قبل از `:443` باید دقیقاً با **Server name (JLS)** در Omni یکی باشد. این نام لزوماً دامنهٔ VPS تو نیست.

نمونه از ALPN برابر `h3`، کنترل ازدحام `bbr` و 0-RTT خاموش استفاده می‌کند. لاگ سطح `info` برای شروع کافی است.

## ۳ · سرویس با کاربر محدود

فایل سرویس را بخوان و سپس نصب کن:

</div>

```sh
id -u omni-shadowquic >/dev/null 2>&1 || \
  sudo useradd --system --user-group --no-create-home --shell /usr/sbin/nologin omni-shadowquic
sudo install -d -o root -g omni-shadowquic -m 0750 /etc/omni-shadowquic
sudo install -o root -g omni-shadowquic -m 0640 server.yaml /etc/omni-shadowquic/server.yaml
sudo install -m 0644 omni-shadowquic.service /etc/systemd/system/omni-shadowquic.service
sudo systemctl daemon-reload
sudo systemctl enable --now omni-shadowquic
sudo systemctl status omni-shadowquic --no-pager
```

<div dir="rtl">

سرویس نمونه کاربر root نیست. برای پورت‌های پایین‌تر از 1024 باید مجوز bind مناسب به unit اضافه شود؛ برای شروع همان 1443 را نگه دار و برای رفع مشکل، سرویس را بی‌دلیل root نکن.

## ۴ · پروفایل در Omni

**+ → Add [ShadowQUIC]**:

| فیلد | مقدار |
| :--- | :--- |
| Address / Port | IP یا دامنهٔ مستقیم VPS / `1443` |
| Username / Password | همان کاربر سرور |
| Server name (JLS) | اگر نمونه را نگه داشتی `cloudflare.com` |
| ALPN | `h3` |
| ShadowQUIC parameters | JSON زیر |

</div>

```json
{"congestion_control":"bbr","over_stream":false,"zero_rtt":false,"keep_alive_interval":10000}
```

<div dir="rtl">

ذخیره کن و وصل شو. گزینهٔ `over_stream` نحوهٔ حمل UDP داخل پروتکل را عوض می‌کند؛ خود ارتباط بیرونی ShadowQUIC همچنان QUIC/UDP است.

قالب لینک نمونه:

</div>

```text
shadowquic://USERNAME:PASSWORD@192.0.2.10:1443?sni=cloudflare.com&alpn=h3&congestion_control=bbr&over_stream=0&zero_rtt=0#My-ShadowQUIC
```

<div dir="rtl">

نشانی `192.0.2.10` صرفاً آموزشی و غیرقابل اتصال است. IP و مشخصات واقعی را جایگزین کن؛ برای IPv6 از کروشه استفاده کن. Share داخل اپ لینک را با escaping درست می‌سازد.

## ۵ · بررسی خطا

</div>

```sh
sudo journalctl -u omni-shadowquic -n 80 --no-pager
sudo ss -lunp | grep ':1443'
```

<div dir="rtl">

اول پورت UDP، نام کاربری، رمز، JLS و ALPN را تطبیق بده. وجود سوکت فقط بالا‌آمدن محلی سرویس را نشان می‌دهد؛ مسیر واقعی گوشی هم باید جواب بدهد. نام JLS اشتباه با دستکاری تصادفی SNI حل نمی‌شود. قبل از تغییر MTU یا 0-RTT، اتصال پایه و لاگ را بررسی کن؛ لاگ خصوصی را عمومی نگذار.

عملکرد به شبکهٔ واقعی وابسته است؛ از نام پروتکل نمی‌شود تضمین پینگ بهتر بازی گرفت.

[نمونهٔ رسمی سرور 0.4.8](https://github.com/spongebob888/shadowquic/blob/v0.4.8/shadowquic/config_examples/server.yaml) · [فایل‌های آمادهٔ نمونه](examples/shadowquic/)

</div>
