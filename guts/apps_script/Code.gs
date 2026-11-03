/**
 * guts — the door (Google Apps Script)
 *
 * Deploy:
 *   1. script.google.com → New project → paste this whole file
 *   2. Set AUTH_KEY to a long random secret, and EXIT_URL to your exit node's /g URL
 *      (the URL the GitHub Actions workflow prints, or your own server)
 *   3. Deploy → New deployment → Web app
 *        Execute as: Me        Who has access: Anyone
 *   4. Copy the /exec URL: that is the door your phone dials (with SNI of a Google host)
 *
 * The door is deliberately dumb: it checks the shared key and hands the body to the exit node,
 * returning the answer unchanged. It never parses destinations, never fetches a target site, and
 * never sees an unencrypted page — that is what keeps it working on a network that only lets a
 * phone reach Google.
 */

const AUTH_KEY = 'CHANGE_ME_TO_A_LONG_RANDOM_SECRET';
const EXIT_URL = 'https://your-exit-node.example/g';

function doPost(e) {
  try {
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
