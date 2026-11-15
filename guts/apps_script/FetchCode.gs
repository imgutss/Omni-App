// Omni GUTS certificate/fetch door.
// Fill in AUTH_KEY, save, then deploy as a Web app with anonymous access.
// This mode does not use an exit node and does not use a /g URL.
const AUTH_KEY = "";

const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
const SKIP_REQUEST_HEADERS = {
  "accept-encoding": 1,
  "connection": 1,
  "content-length": 1,
  "forwarded": 1,
  "host": 1,
  "keep-alive": 1,
  "priority": 1,
  "proxy-authorization": 1,
  "proxy-connection": 1,
  "te": 1,
  "trailer": 1,
  "transfer-encoding": 1,
  "upgrade": 1,
  "via": 1,
  "x-forwarded-for": 1,
  "x-forwarded-host": 1,
  "x-forwarded-port": 1,
  "x-forwarded-proto": 1,
  "x-real-ip": 1
};
const ALLOWED_METHODS = {
  GET: 1,
  HEAD: 1,
  POST: 1,
  PUT: 1,
  PATCH: 1,
  DELETE: 1,
  OPTIONS: 1
};

function doGet() {
  return ContentService.createTextOutput("guts fetch door is running. POST only.\n")
    .setMimeType(ContentService.MimeType.TEXT);
}

function doPost(e) {
  try {
    if (!AUTH_KEY) return json_({ ok: false, e: "AUTH_KEY is empty in the Apps Script code" });
    if (!e || !e.postData || !e.postData.contents) return json_({ ok: false, e: "missing body" });

    var req = JSON.parse(e.postData.contents);
    if (!req || req.k !== AUTH_KEY) return json_({ ok: false, e: "unauthorized" });

    if (req.op === "ping") {
      return json_({ ok: true, info: "guts-fetch/0.1.0 fetch" });
    }
    if (req.op !== "fetch") return json_({ ok: false, e: "unsupported operation" });

    var originalMethod = String(req.method || "GET").toUpperCase();
    if (!ALLOWED_METHODS[originalMethod]) {
      return json_({ ok: false, e: "unsupported method: " + originalMethod });
    }
    if (!req.url || typeof req.url !== "string" || !/^https?:\/\//i.test(req.url)) {
      return json_({ ok: false, e: "bad url" });
    }

    // UrlFetchApp accepts get/delete/patch/post/put. HEAD is fetched as GET
    // and its body is discarded below. The client answers OPTIONS locally;
    // this branch only keeps a direct API caller from failing on method.
    var fetchMethod = originalMethod;
    if (fetchMethod === "HEAD" || fetchMethod === "OPTIONS") fetchMethod = "GET";

    var params = {
      method: fetchMethod.toLowerCase(),
      headers: buildHeaders_(req.headers),
      muteHttpExceptions: true,
      followRedirects: false,
      validateHttpsCertificates: true,
      escaping: false
    };

    if (fetchMethod !== "GET" && req.d) {
      var contentType = findHeader_(req.headers, "content-type") || "application/octet-stream";
      params.contentType = contentType;
      params.payload = Utilities.newBlob(Utilities.base64Decode(req.d), contentType);
    }

    var resp = UrlFetchApp.fetch(req.url, params);
    var headers = normalizeResponseHeaders_(resp);
    var encoded = "";

    if (originalMethod !== "HEAD") {
      var bytes = resp.getContent();
      if (bytes.length > MAX_RESPONSE_BYTES) {
        return json_({
          ok: false,
          e: "response is larger than one Apps Script fetch may carry; use an HTTP Range download"
        });
      }
      encoded = Utilities.base64Encode(bytes);
    }

    return json_({
      ok: true,
      status: resp.getResponseCode(),
      headers: headers,
      d: encoded
    });
  } catch (err) {
    return json_({ ok: false, e: "fetch failed: " + errorText_(err) });
  }
}

function buildHeaders_(input) {
  var out = { "Accept-Encoding": "identity" };
  if (!input || typeof input !== "object") return out;

  for (var name in input) {
    if (!Object.prototype.hasOwnProperty.call(input, name)) continue;
    var lower = String(name).toLowerCase();
    if (SKIP_REQUEST_HEADERS[lower]) continue;

    var value = input[name];
    if (Array.isArray(value)) {
      value = value.join(lower === "cookie" ? "; " : ", ");
    }
    out[name] = String(value);
  }
  return out;
}

function findHeader_(input, wantedLower) {
  if (!input || typeof input !== "object") return "";
  for (var name in input) {
    if (!Object.prototype.hasOwnProperty.call(input, name)) continue;
    if (String(name).toLowerCase() !== wantedLower) continue;
    var value = input[name];
    return Array.isArray(value) ? String(value[0] || "") : String(value || "");
  }
  return "";
}

function normalizeResponseHeaders_(resp) {
  var raw = {};
  try {
    raw = typeof resp.getAllHeaders === "function" ? resp.getAllHeaders() : resp.getHeaders();
  } catch (err) {
    raw = {};
  }

  var out = {};
  for (var name in raw) {
    if (!Object.prototype.hasOwnProperty.call(raw, name)) continue;
    var value = raw[name];
    if (Array.isArray(value)) {
      out[name] = value.map(function (part) { return String(part); });
    } else if (value !== null && value !== undefined) {
      out[name] = [String(value)];
    }
  }
  return out;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function errorText_(err) {
  if (err && err.message) return String(err.message);
  return String(err);
}
