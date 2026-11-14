/**
 * WebRTC Link - GAS signaling endpoint
 * GAS does NOT relay camera or microphone media.
 * Deploy as Web app: Execute as Me / Who has access: Anyone.
 */
const ROOM_TTL_SECONDS = 21600; // 6 hours
const MAX_MESSAGES_PER_QUEUE = 45;

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (p.action === "poll") return pollMessages_(p);
  if (p.action === "health") return jsonpOutput_({ ok: true, service: "WebRTC Link Signaling", version: "0.3" }, p.callback);
  if (p.action === "pair_complete") return pairComplete_(p);
  if (p.action === "device_status") return deviceStatus_(p);
  return output_({ ok: true, service: "WebRTC Link Signaling", version: "0.3" });
}

function doPost(e) {
  try {
    const raw = e && e.postData && e.postData.contents;
    if (!raw) return output_({ ok: false, error: "empty_body" });
    const m = JSON.parse(raw);
    if (m.action === "pair_start") return pairStart_(m);
    if (!validMessage_(m)) return output_({ ok: false, error: "invalid_message" });
    if (m.type === "device_status") {
      CacheService.getScriptCache().put("device_presence_" + m.deviceId, JSON.stringify({
        online: m.payload.online !== false,
        room: String(m.room),
        deviceLabel: String(m.payload.deviceLabel || "Android端末").slice(0, 80),
        batteryPercent: m.payload.batteryPercent,
        networkType: String(m.payload.networkType || "不明").slice(0, 30),
        timestamp: Date.now()
      }), 65);
    }
    const key = queueKey_(m.room, m.to);
    const lock = LockService.getScriptLock();
    lock.waitLock(8000);
    try {
      const cache = CacheService.getScriptCache();
      const current = JSON.parse(cache.get(key) || "[]");
      current.push({
        id: String(m.id),
        deviceId: String(m.deviceId),
        room: String(m.room),
        from: String(m.from),
        to: String(m.to),
        type: String(m.type),
        payload: m.payload,
        time: Date.now()
      });
      const recent = current.slice(-MAX_MESSAGES_PER_QUEUE);
      cache.put(key, JSON.stringify(recent), ROOM_TTL_SECONDS);
    } finally {
      lock.releaseLock();
    }
    return output_({ ok: true });
  } catch (err) {
    return output_({ ok: false, error: String(err && err.message || err) });
  }
}

function pollMessages_(p) {
  const room = String(p.room || "");
  const role = String(p.role || "");
  const callback = String(p.callback || "");
  if (!/^\d{6}$/.test(room) || !/^(android|ipad)$/.test(role)) {
    return output_({ ok: false, error: "invalid_room_or_role" });
  }
  const deviceId = String(p.deviceId || "");
  const authToken = String(p.authToken || "");
  if (!authorizedDevice_(deviceId, authToken)) {
    return jsonpOutput_({ ok: false, error: "unauthorized", messages: [] }, callback);
  }
  const cache = CacheService.getScriptCache();
  const messages = JSON.parse(cache.get(queueKey_(room, role)) || "[]")
    .filter(function(message) { return message.deviceId === deviceId; });
  const result = { ok: true, messages: messages };
  if (/^[A-Za-z_$][0-9A-Za-z_$]{0,60}$/.test(callback)) {
    return ContentService.createTextOutput(callback + "(" + JSON.stringify(result) + ");")
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return output_(result);
}

function validMessage_(m) {
  if (!m || !/^\d{6}$/.test(String(m.room || ""))) return false;
  if (!/^(android|ipad)$/.test(String(m.from || ""))) return false;
  if (!/^(android|ipad)$/.test(String(m.to || "")) || m.from === m.to) return false;
  if (!/^(offer|answer|candidate|hangup|location_request|location_result|location_denied|location_error|device_status)$/.test(String(m.type || ""))) return false;
  if (!m.id || String(m.id).length > 100) return false;
  if (!authorizedDevice_(String(m.deviceId || ""), String(m.authToken || ""))) return false;
  if (m.type === "location_request") {
    if (m.from !== "ipad" || m.to !== "android" || !m.payload || m.payload.oneShot !== true) return false;
  }
  if (m.type === "location_result") {
    if (m.from !== "android" || m.to !== "ipad" || !m.payload) return false;
    const lat = Number(m.payload.latitude), lng = Number(m.payload.longitude);
    if (!Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lng) || lng < -180 || lng > 180) return false;
  }
  if ((m.type === "location_denied" || m.type === "location_error") && (m.from !== "android" || m.to !== "ipad")) return false;
  if (m.type === "device_status") {
    if (m.from !== "android" || m.to !== "ipad" || !m.payload) return false;
    if (String(m.payload.deviceLabel || "").length > 80) return false;
    if (m.payload.online !== undefined && typeof m.payload.online !== "boolean") return false;
    const battery = m.payload.batteryPercent;
    if (battery !== null && (!Number.isFinite(Number(battery)) || Number(battery) < 0 || Number(battery) > 100)) return false;
  }
  const text = JSON.stringify(m.payload);
  return text.length > 0 && text.length < 50000;
}

function pairStart_(m) {
  const deviceId = String(m.deviceId || "");
  const pairCode = String(m.pairCode || "");
  const authToken = String(m.authToken || "");
  const label = String(m.label || "Android端末").slice(0, 80);
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(deviceId) ||
      !/^\d{8}$/.test(pairCode) ||
      !/^[A-Za-z0-9_-]{40,100}$/.test(authToken)) {
    return output_({ ok: false, error: "invalid_pair_request" });
  }
  const lock = LockService.getScriptLock();
  lock.waitLock(8000);
  try {
    const props = PropertiesService.getScriptProperties();
    props.setProperty("device_auth_" + deviceId, authToken);
    CacheService.getScriptCache().put("pair_code_" + pairCode,
      JSON.stringify({ deviceId: deviceId, authToken: authToken, label: label }), 300);
  } finally {
    lock.releaseLock();
  }
  return output_({ ok: true, expiresInSeconds: 300 });
}

function pairComplete_(p) {
  const callback = String(p.callback || "");
  const pairCode = String(p.code || "");
  if (!/^\d{8}$/.test(pairCode)) return jsonpOutput_({ ok: false, error: "invalid_pair_code" }, callback);
  const lock = LockService.getScriptLock();
  lock.waitLock(8000);
  try {
    const cache = CacheService.getScriptCache();
    const attempts = Number(cache.get("pair_attempts") || 0);
    if (attempts >= 30) return jsonpOutput_({ ok: false, error: "rate_limited" }, callback);
    cache.put("pair_attempts", String(attempts + 1), 60);
    const key = "pair_code_" + pairCode;
    const raw = cache.get(key);
    if (!raw) return jsonpOutput_({ ok: false, error: "code_expired_or_invalid" }, callback);
    cache.remove(key);
    const data = JSON.parse(raw);
    return jsonpOutput_({ ok: true, deviceId: data.deviceId, authToken: data.authToken, label: data.label }, callback);
  } finally {
    lock.releaseLock();
  }
}

function deviceStatus_(p) {
  const callback = String(p.callback || "");
  const deviceId = String(p.deviceId || "");
  const authToken = String(p.authToken || "");
  if (!authorizedDevice_(deviceId, authToken)) {
    return jsonpOutput_({ ok: false, error: "unauthorized", online: false }, callback);
  }
  const raw = CacheService.getScriptCache().get("device_presence_" + deviceId);
  const presence = raw ? JSON.parse(raw) : { online: false };
  return jsonpOutput_({ ok: true, ...presence }, callback);
}

function authorizedDevice_(deviceId, authToken) {
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(deviceId) || !/^[A-Za-z0-9_-]{40,100}$/.test(authToken)) return false;
  const saved = PropertiesService.getScriptProperties().getProperty("device_auth_" + deviceId);
  return !!saved && saved === authToken;
}

function queueKey_(room, role) {
  return "rtc_" + room + "_" + role;
}

function jsonpOutput_(data, callback) {
  const name = String(callback || "");
  if (/^[A-Za-z_$][0-9A-Za-z_$]{0,60}$/.test(name)) {
    return ContentService.createTextOutput(name + "(" + JSON.stringify(data) + ");")
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return output_(data);
}

function output_(data) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}