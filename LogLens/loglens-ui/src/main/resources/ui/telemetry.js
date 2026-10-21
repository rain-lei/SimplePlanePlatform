'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const form = $('telemetry-filters');
  const rows = new Map();
  let active = [];
  let liveCursor = 0;
  let historyStart = 0;
  let historyThrough = 0;
  let loading = false;
  let buffered = [];
  let selectedTab = 'logs';
  const filters = () => Object.fromEntries([...new FormData(form)].filter(([, value]) => value));
  const query = (after, through, values = filters()) => {
    const p = new URLSearchParams({cursor: String(after), through: String(through), limit: '100'});
    Object.entries(values).forEach(([key, value]) => { const normalized = key === 'fromTime' || key === 'toTime' ? String(Date.parse(value) || 0) : value; if (normalized !== '0') p.set(key, normalized); });
    return p;
  };
  const showError = error => { $('connection').textContent = 'Telemetry query failed · ' + error.message; };
  const bytes = value => {
    let n = Number(value || 0), unit = 'B';
    for (const next of ['KB', 'MB', 'GB', 'TB']) { if (n < 1024) break; n /= 1024; unit = next; }
    return (unit === 'B' ? n.toFixed(0) : n.toFixed(1)) + ' ' + unit;
  };
  const label = event => ({CLIENT_CONNECTED:'Connected', CLIENT_DISCONNECTED:'Disconnected', SESSION_OPENED:'Session opened', SESSION_ACTIVE:'Session active', SESSION_CLOSED:'Session closed', SESSION_CONNECT_FAILED:'Connect failed', TRAFFIC_SAMPLE:'Traffic sample'})[event.kind] || event.kind;
  function key(event) { return event.streamId ? 's:' + (event.connectionId || '') + ':' + event.streamId : event.connectionId ? 'c:' + event.connectionId : event.eventId; }
  function put(event) {
    if (!event || !event.kind) return;
    const id = key(event), old = rows.get(id);
    if (!old || Number(event.captureSequence || 0) >= Number(old.captureSequence || 0) || Number(event.capturedAt || 0) >= Number(old.capturedAt || 0)) rows.set(id, event);
    render();
  }
  function matches(event) {
    const f = filters();
    return (!f.peerAddress || (event.peerAddress || '').includes(f.peerAddress)) && (!f.transport || event.transport === f.transport) && (!f.target || (event.targetHost || '').includes(f.target)) && (!f.status || event.status === f.status) && (!f.fromTime || event.eventTime >= Date.parse(f.fromTime)) && (!f.toTime || event.eventTime <= Date.parse(f.toTime));
  }
  async function pageRange(after, through, targetRows = rows) {
    let cursor = after;
    for (;;) {
      const result = await window.loglensApi('/api/telemetry/events?' + query(cursor, through));
      result.items.forEach(item => { if (matches(item)) targetRows.set(key(item), item); });
      if (!result.hasMore || result.nextCursor <= cursor) return result.nextCursor;
      cursor = result.nextCursor;
    }
  }
  async function initialize(watermark, reset = false) {
    if (loading) return;
    loading = true; buffered = [];
    try {
      const health = await window.loglensApi('/api/health');
      const through = Math.min(watermark || health.telemetryWatermark || 0, health.telemetryWatermark || 0);
      if (reset || historyThrough === 0) {
        historyThrough = through;
        historyStart = Math.max(0, through - 1000);
        liveCursor = historyStart;
        rows.clear();
      } else historyThrough = Math.max(historyThrough, through);
      await pageRange(liveCursor, through);
      liveCursor = Math.max(liveCursor, through);
      buffered.sort((a, b) => a.captureSequence - b.captureSequence).forEach(item => { if (item.captureSequence > liveCursor && matches(item)) put(item); liveCursor = Math.max(liveCursor, item.captureSequence); });
      loading = false;
      await refreshActive();
      render();
      $('connection').textContent = '● Telemetry live';
    } catch (error) { loading = false; showError(error); }
  }
  async function refreshActive() {
    try {
      const result = await window.loglensApi('/api/telemetry/connections');
      active = result.items || [];
      active.forEach(put);
      renderSummary();
    } catch (error) { showError(error); }
  }
  function renderSummary() {
    const sessionRows = active;
    $('active-count').textContent = String(sessionRows.filter(item => item.streamId).length);
    $('connection-count').textContent = String(new Set(sessionRows.map(item => item.connectionId).filter(Boolean)).size);
    $('uplink-total').textContent = bytes(sessionRows.reduce((sum, item) => sum + Number(item.uplinkBytes || 0), 0));
    $('downlink-total').textContent = bytes(sessionRows.reduce((sum, item) => sum + Number(item.downlinkBytes || 0), 0));
  }
  function render() {
    if (!$('connections-view') || $('connections-view').hidden) return;
    const tbody = $('telemetry-rows'); tbody.replaceChildren();
    const items = [...rows.values()].filter(matches).sort((a, b) => (b.eventTime || 0) - (a.eventTime || 0)).slice(0, 500);
    for (const event of items) {
      const tr = document.createElement('tr');
      const values = [new Date(event.eventTime || event.capturedAt || 0).toLocaleString(), label(event) + ' · ' + (event.status || '—'), event.peerAddress || '—', event.transport || '—', event.streamId || '—', (event.targetHost || '—') + (event.targetPort ? ':' + event.targetPort : ''), bytes(event.uplinkBytes), bytes(event.downlinkBytes)];
      values.forEach((value, index) => { const td = document.createElement('td'); td.textContent = value; if (index === 5) td.className = 'target'; tr.append(td); });
      tr.title = 'Click to search logs by Stream ID';
      tr.onclick = () => { if (!event.streamId) return; const logForm = $('filters'); logForm.elements.namedItem('q').value = event.streamId; $('logs-tab').click(); logForm.requestSubmit(); };
      tbody.append(tr);
    }
  }
  async function loadOlder() {
    if (historyStart <= 0) return;
    const through = historyStart, after = Math.max(0, historyStart - 1000);
    try {
      await pageRange(after, through); historyStart = after; $('telemetry-next').disabled = historyStart === 0;
    } catch (error) { showError(error); }
  }
  $('logs-tab').onclick = () => { selectedTab = 'logs'; $('logs-tab').classList.add('active'); $('connections-tab').classList.remove('active'); $('viewport').hidden = false; $('health').hidden = false; $('connections-view').hidden = true; $('detail').hidden = false; $('view-title').textContent = 'Event stream'; };
  $('connections-tab').onclick = () => { selectedTab = 'connections'; $('connections-tab').classList.add('active'); $('logs-tab').classList.remove('active'); $('viewport').hidden = true; $('health').hidden = true; $('connections-view').hidden = false; $('detail').hidden = true; $('view-title').textContent = 'Connections and sessions'; initialize(historyThrough).catch(showError); };
  form.onsubmit = event => { event.preventDefault(); rows.clear(); historyStart = Math.max(0, liveCursor - 1000); initialize(liveCursor, true).catch(showError); };
  $('telemetry-next').onclick = loadOlder;
  window.telemetryEvent = event => { if (loading) { buffered.push(event); return; } if (event.captureSequence > liveCursor) liveCursor = event.captureSequence; if (matches(event)) put(event); };
  window.telemetryReady = watermark => { if (selectedTab === 'connections') initialize(watermark).catch(showError); else { liveCursor = Math.max(liveCursor, watermark); } };
  window.loglensApi = async path => { const response = await fetch(path); if (!response.ok) throw new Error(String(response.status)); return response.json(); };
  setInterval(() => { if (selectedTab === 'connections') refreshActive().catch(showError); }, 2000);
  $('telemetry-next').disabled = true;
})();