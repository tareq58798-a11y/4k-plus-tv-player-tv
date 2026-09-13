function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function layout(body) {
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>4K Plus TV - Device Activation</title>
<style>
  body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; padding: 24px; background: #0b1220; color: #e6edf3; }
  h1 { font-size: 22px; margin-bottom: 4px; }
  table { width: 100%; border-collapse: collapse; margin-top: 16px; background: #131b2c; border-radius: 10px; overflow: hidden; }
  th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #223049; font-size: 14px; }
  th { background: #1b263b; color: #9fb3d1; font-weight: 600; }
  tr:last-child td { border-bottom: none; }
  .pending { color: #f0a020; font-weight: 600; }
  .assigned { color: #3ad07a; font-weight: 600; }
  form.assign-form { display: flex; flex-direction: column; gap: 6px; min-width: 260px; }
  input, select { background: #0b1220; border: 1px solid #2a3a56; color: #e6edf3; padding: 6px 8px; border-radius: 6px; font-size: 13px; }
  button { background: #2f6feb; color: white; border: none; padding: 7px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; }
  button.secondary { background: #3a4a68; }
  .row { display: flex; gap: 6px; }
  .muted { color: #7d8ba1; font-size: 12px; }
</style>
</head>
<body>
${body}
<script>
document.querySelectorAll('select[data-type-toggle]').forEach(function(select) {
  function update() {
    var form = select.closest('form');
    form.querySelectorAll('[data-m3u]').forEach(function(el){ el.style.display = select.value === 'm3u' ? '' : 'none'; });
    form.querySelectorAll('[data-xtream]').forEach(function(el){ el.style.display = select.value === 'xtream' ? '' : 'none'; });
  }
  select.addEventListener('change', update);
  update();
});
</script>
</body>
</html>`;
}

function renderDevices(devices) {
  const rows = devices.map((device) => {
    const statusClass = device.status === 'assigned' ? 'assigned' : 'pending';
    const mac = escapeHtml(device.mac);
    return `<tr>
      <td>${mac}</td>
      <td>${escapeHtml(device.device_key)}</td>
      <td class="${statusClass}">${escapeHtml(device.status)}</td>
      <td>${device.playlist_type ? escapeHtml(device.playlist_type) : '-'}</td>
      <td class="muted">${escapeHtml(new Date(device.last_seen).toLocaleString())}</td>
      <td>
        <form class="assign-form" method="post" action="/admin/devices/${encodeURIComponent(mac)}/assign">
          <input type="text" name="playlistName" placeholder="Playlist name" value="${escapeHtml(device.playlist_name || '')}" required>
          <select name="type" data-type-toggle>
            <option value="m3u" ${device.playlist_type === 'm3u' ? 'selected' : ''}>M3U URL</option>
            <option value="xtream" ${device.playlist_type === 'xtream' ? 'selected' : ''}>Xtream login</option>
          </select>
          <input data-m3u type="text" name="m3uUrl" placeholder="M3U URL" value="${escapeHtml(device.m3u_url || '')}">
          <input data-xtream type="text" name="xtreamServer" placeholder="Server (http://host)" value="${escapeHtml(device.xtream_server || '')}">
          <input data-xtream type="text" name="xtreamUsername" placeholder="Username" value="${escapeHtml(device.xtream_username || '')}">
          <input data-xtream type="text" name="xtreamPassword" placeholder="Password" value="${escapeHtml(device.xtream_password || '')}">
          <div class="row">
            <button type="submit">Save</button>
            ${device.status === 'assigned' ? `<button type="submit" formaction="/admin/devices/${encodeURIComponent(mac)}/unassign" formnovalidate class="secondary">Unassign</button>` : ''}
          </div>
        </form>
      </td>
    </tr>`;
  }).join('');

  return layout(`
    <h1>4K Plus TV — Device Activation</h1>
    <p class="muted">Devices appear here automatically the first time they check in from the app's Activation screen.</p>
    <table>
      <thead><tr><th>MAC</th><th>Device key</th><th>Status</th><th>Type</th><th>Last seen</th><th>Assign playlist</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="6" class="muted">No devices have checked in yet.</td></tr>'}</tbody>
    </table>
  `);
}

module.exports = { renderDevices };
