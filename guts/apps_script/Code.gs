/**
 * guts — the door (Google Apps Script)
 * ═══════════════════════════════════════════════════════════════════════════
 * این فایل را همین‌طور کپی کن. فقط دو خطِ مشخص‌شده با «✏️ ویرایش کن» را عوض کن:
 *
 *   AUTH_KEY  ← یک رمز طولانی و تصادفی؛ عینِ همین رمز باید در خروجی (Exit)
 *               و در اپ گوشی (فیلد Shared key) هم باشد. هر سه جا یکی.
 *   EXIT_URL  ← آدرس خروجی با پسوند /g، مثلاً
 *               https://xxxx-xxxx.trycloudflare.com/g
 *               این آدرس را اجرای ورک‌فلوی «guts exit» در گیت‌هاب چاپ می‌کند
 *               (یا آدرس سرور خودت اگر خروجی را روی VPS بالا آوردی).
 *
 * مراحل (قدم‌به‌قدم کامل در guts/SETUP.fa.md):
 *   ۱) script.google.com ← ورود با اکانت گوگل ← ‏New project
 *   ۲) کد پیش‌فرض داخل ویرایشگر را پاک کن و کل این فایل را بچسبان
 *   ۳) دو خط زیر را ویرایش کن و ‏Save بزن (Ctrl+S)
 *   ۴) ‏Deploy ← ‏New deployment ← چرخ‌دنده ⚙ ← ‏Web app
 *        ‏Execute as: ‏Me        ‏Who has access: ‏Anyone
 *   ۵) ‏Deploy ← اگر اجازه خواست ‏Authorize access را تأیید کن
 *   ۶) آدرس ‏/exec را کپی کن؛ این می‌رود در اپ گوشی، فیلد Doors
 *
 * دفعه‌های بعد که فقط EXIT_URL عوض شد (هر اجرای تازه‌ی خروجی آدرس تازه می‌دهد):
 *   خط را ویرایش کن ← Deploy ← Manage deployments ← مداد ✏️ ←
 *   Version: New version ← Deploy  — آدرس ‎/exec ثابت می‌ماند.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The door is deliberately dumb: it checks the shared key and hands the body to the exit node,
 * returning the answer unchanged. It never parses destinations, never fetches a target site, and
 * never sees an unencrypted page — that is what keeps it working on a network that only lets a
 * phone reach Google.
 */

// ✏️ ویرایش کن (۱ از ۲): همان رمزی که در خروجی و در اپ می‌گذاری. طولانی و تصادفی باشد.
const AUTH_KEY = 'CHANGE_ME_TO_A_LONG_RANDOM_SECRET';

// ✏️ ویرایش کن (۲ از ۲): آدرس خروجی با /g در انتها.
const EXIT_URL = 'https://your-exit-node.example/g';

function doPost(e) {
  try {
    // A door that still carries the placeholders can only ever answer errors; say so plainly
    // instead of letting the phone debug a failed fetch to a made-up host.
    if (AUTH_KEY.indexOf('CHANGE_ME') === 0) {
      return out({ ok: false, e: 'door: AUTH_KEY is still the placeholder — edit Code.gs and redeploy a new version' });
    }
    if (EXIT_URL.indexOf('your-exit-node.example') !== -1) {
      return out({ ok: false, e: 'door: EXIT_URL is still the placeholder — run the exit node, paste its /g URL, and redeploy a new version' });
    }

    var body = (e && e.postData && e.postData.contents) || '';
    var req;
    try {
      req = JSON.parse(body);
    } catch (err) {
      return out({ ok: false, e: 'json: ' + err });
    }
    if (req.k !== AUTH_KEY) return out({ ok: false, e: 'unauthorized' });

    var resp = UrlFetchApp.fetch(EXIT_URL, {
      method: 'post',
      contentType: 'application/json',
      payload: body,
      headers: { 'X-Guts-Key': AUTH_KEY },
      muteHttpExceptions: true,
      followRedirects: false,
    });

    var code = resp.getResponseCode();
    var text = resp.getContentText();

    // An exit node answers JSON. Anything else (a Cloudflare challenge, a captive portal, a broken
    // tunnel) is reported as an error instead of being handed to the phone as if it were data.
    try {
      JSON.parse(text);
    } catch (err) {
      return out({ ok: false, e: 'exit answered non-JSON, status ' + code + ': ' + text.slice(0, 160) });
    }
    if (code !== 200) {
      return out({ ok: false, e: 'exit status ' + code + ': ' + text.slice(0, 160) });
    }
    return ContentService.createTextOutput(text).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return out({ ok: false, e: 'door: ' + err });
  }
}

/** A GET is a health check: it tells a phone that the deployment exists without using a fetch. */
function doGet() {
  return out({ ok: true, info: 'guts-door/0.1.0' });
}

function out(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
