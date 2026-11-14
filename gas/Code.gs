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
  return output_({ ok: true, service: "WebRTC Link Signaling", version: "0.1" });
}

function doPost(e) {
  try {
    const raw = e && e.postData && e.postData.contents;
    if (!raw) return output_({ ok: false, error: "empty_body" });
    const m = JSON.parse(raw);
    if (!validMessage_(m)) return output_({ ok: false, error: "invalid_message" });
    const key = queueKey_(m.room, m.to);
    const lock = LockService.getScriptLock();
    lock.waitLock(8000);
    try {
      const cache = CacheService.getScriptCache();
      const current = JSON.parse(cache.get(key) || "[]");
      current.push({
        id: String(m.id),
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
  const cache = CacheService.getScriptCache();
  const messages = JSON.parse(cache.get(queueKey_(room, role)) || "[]");
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
    const battery = m.payload.batteryPercent;
    if (battery !== null && (!Number.isFinite(Number(battery)) || Number(battery) < 0 || Number(battery) > 100)) return false;
  }
  const text = JSON.stringify(m.payload);
  return text.length > 0 && text.length < 50000;
}

function queueKey_(room, role) {
  return "rtc_" + room + "_" + role;
}

function output_(data) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}