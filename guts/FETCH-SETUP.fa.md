<div dir="rtl">

# حالت گواهی / Fetch — بدون نود خروجی

این حالت برای وقتی است که نمی‌خواهی خروجی موقت، `EXIT_URL` یا نشانی ‎`/g` داشته باشی.
خودِ Apps Script سایت را fetch می‌کند و گوشی برای دیدن و دوباره رمزکردن HTTPS از یک CA محلیِ متعلق به خودت استفاده می‌کند.

```text
مرورگر ──HTTPS──► Omni روی گوشی (گواهی از CA شخصی تو)
        ──HTTP داخل تونل گوگل──► Apps Script تو (/exec)
        ──UrlFetchApp──► سایت مقصد
```

## ۱) یک رمز تازه بساز

یک رشته‌ی طولانی و تصادفی، بدون فاصله. دقیقاً همین رمز باید در دو جا باشد:

- خط `AUTH_KEY` در `apps_script/FetchCode.gs`
- فیلد **Shared key** پروفایل GUTS در Omni

این رمز را با آدرس `/exec` منتشر نکن؛ هر کس هر دو را داشته باشد از سهمیه‌ی اکانت گوگلت استفاده می‌کند.

## ۲) Apps Script را دیپلوی کن

1. به `script.google.com` برو و یک پروژه‌ی تازه بساز، یا همان پروژه‌ی قبلی را باز کن.
2. کد قبلی `Code.gs` را کامل پاک کن.
3. کل محتوای [`apps_script/FetchCode.gs`](apps_script/FetchCode.gs) را بچسبان. این فایل عمداً فقط ASCII است و متن فارسی ندارد.
4. فقط خط اول تنظیمات را پر کن:

```js
const AUTH_KEY = "the-long-random-secret-you-chose";
```

5. حتماً **Save** بزن؛ بالای ویرایشگر نباید «Unsaved changes» مانده باشد.
6. برای پروژه‌ی تازه: **Deploy ← New deployment ← Web app**، با این دو انتخاب:
   - **Execute as:** Me
   - **Who has access:** Anyone
7. برای پروژه‌ای که از قبل دیپلوی دارد: **Deploy ← Manage deployments ← مداد ← Version: New version ← Deploy**.
   فقط Save کافی نیست و آدرس `/exec` با New version ثابت می‌ماند.
8. اگر گوگل اجازه خواست، دسترسی اسکریپت خودت را تأیید کن.
9. نشانی نهایی `/exec` را بردار.

## ۳) گواهی شخصی گوشی را بساز و نصب کن

در Omni نسخه‌ی 0.11.7 به بعد:

1. یک پروفایل GUTS بساز یا پروفایل قبلی را باز کن.
2. کلید **Certificate mode (Apps Script fetch)** را روشن کن.
3. روی **Create / install my certificate** بزن.
4. Omni یک CA پایدار برای همین گوشی می‌سازد، فایل عمومی `omni-guts-ca.crt` را در Downloads ذخیره می‌کند و نصب‌کننده یا تنظیمات را باز می‌کند.
5. در اندروید آن را به‌عنوان **CA certificate** نصب کن. مسیر دقیق در برندهای مختلف فرق دارد؛ معمولاً:
   Settings ← Security / Biometrics and security ← Encryption and credentials / Install a certificate ← CA certificate.
6. اگر اندروید اثرانگشت نشان داد، می‌توانی با SHA-256 زیر دکمه در Omni مقایسه‌اش کنی.

کلید خصوصی CA داخل فضای خصوصی Omni می‌ماند؛ چیزی که نصب می‌کنی فقط گواهی عمومی است.
این CA را با کس دیگری شریک نشو و CA فرد دیگری را هم نصب نکن: صاحب CA از نظر فنی می‌تواند برای گوشی تو گواهی هر سایتی را بسازد.

## ۴) پروفایل را پر کن

- **Certificate mode:** روشن
- **Doors:** نشانی `/exec` قدم ۲، هر خط یکی
- **Shared key:** همان رمز قدم ۱
- **Google edge address:** اول `auto`
- **Edge SNI:** اول `www.google.com`

ذخیره کن و وصل شو. اول با یک سایت معمولیِ غیرگوگلی در مرورگری که به CA کاربر اعتماد می‌کند تست کن.

## روی کامپیوتر

```bash
gutsclient -make-ca -ca-cert ~/.config/guts/ca.crt -ca-key ~/.config/guts/ca.key

gutsclient -mode fetch \
  -ca-cert ~/.config/guts/ca.crt -ca-key ~/.config/guts/ca.key \
  -door "https://script.google.com/macros/s/XXXX/exec" \
  -key "the-long-random-secret-you-chose" \
  -google-ip auto -listen 127.0.0.1:1080
```

CA عمومی را در trust store همان سیستم/مرورگر نصب کن و مرورگر را روی SOCKS5 محلی بگذار.

## محدودیت‌های واقعی

- این حالت **HTTP/HTTPS وب** است، نه TCP خام: UDP، تماس، بازی و پروتکل‌های غیراHTTP را نمی‌برد.
- فقط برنامه‌هایی کار می‌کنند که به CA نصب‌شده توسط کاربر اعتماد کنند. روی اندروید معمولاً مرورگرها چنین‌اند؛ بیشتر اپ‌های بسته فقط CA سیستمی را قبول می‌کنند و certificate pinning هم جلوی آن را می‌گیرد.
- خود `UrlFetchApp` مقصدهای متعلق به گوگل، مانند `*.google.com` و `*.gstatic.com`، را رد می‌کند؛ این محدودیت گوگل است و با عوض‌کردن کد دور زده نمی‌شود.
- WebSocket و پاسخ‌های خیلی بزرگ در یک fetch پشتیبانی نمی‌شوند. دانلودهایی که HTTP Range دارند بهتر کار می‌کنند.
- هر درخواست وب یک فراخوانی Apps Script است و از سهمیه‌ی روزانه‌ی همان اکانت کم می‌کند. چند در از چند اکانت، سهمیه و پایداری را بهتر می‌کند.
- چون HTTPS روی گوشی باز و بسته می‌شود، Apps Script درخواست وب و پاسخ آن را می‌بیند. برای حساب‌های حساس و banking از این حالت استفاده نکن.

## خطاهای رایج

| نشانه | علت محتمل | کار درست |
|---|---|---|
| `unauthorized` | رمز Omni و `AUTH_KEY` یکی نیست | هر دو را دقیقاً یکی کن و برای اسکریپت New version بزن |
| `does not advertise fetch` | دیپلوی هنوز کد قدیمی exit است | `FetchCode.gs` را بچسبان، Save کن و **New version** دیپلوی کن |
| خطای گواهی در مرورگر | CA نصب نشده، یا مرورگر/اپ به CA کاربر اعتماد نمی‌کند | نصب CA را کامل کن و با یک مرورگر دیگر هم تست کن |
| پینگ هست ولی HTTPS باز نمی‌شود | پینگ Omni مسیر HTTP را می‌سنجد؛ CA یا اعتماد اپ جدا است | نصب CA و fingerprint را بررسی کن |
| سایت گوگلی باز نمی‌شود | محدودیت خود UrlFetchApp | برای آن مقصد این حالت کافی نیست |
| وصل می‌شود ولی سایت عادی نمی‌آید | Save شده ولی نسخه‌ی تازه دیپلوی نشده، یا Apps Script سهمیه ندارد | Manage deployments ← New version؛ چند در از اکانت‌های دیگر اضافه کن |

</div>
