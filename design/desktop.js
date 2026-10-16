// Shared window chrome for the desktop mockups: <div class="win" data-tab="tasks"> gets a title bar and sidebar.
document.querySelectorAll('.win[data-tab]').forEach(w => {
  const tab = w.dataset.tab;
  const nv = (id, icon, name, extra = '') =>
    `<div class="nv${id === tab ? ' on' : ''}"><i>${icon}</i>${name}${extra}</div>`;
  const main = w.innerHTML;
  w.innerHTML = `
  <div class="titlebar"><div class="ti"><div class="lg"></div>Vault Capture</div><div class="ctl"><span>—</span><span>☐</span><span>✕</span></div></div>
  <div class="body">
    <div class="side">
      <div class="vault"><b>Vault Capture</b>D:\\Obsidian\\Vault</div>
      <div class="btn" style="padding:11px;font-size:14px;margin:0 2px 16px;display:flex;justify-content:center;gap:10px;align-items:center">＋ Capture <span class="kbd" style="background:#0002;border-color:#0003;color:var(--on-primary)">Ctrl N</span></div>
      ${nv('tasks', '✓', 'Tasks', '<span class="ct red">1 overdue</span>')}
      ${nv('log', '☀', 'Daily log')}
      ${nv('ideas', '💡', 'Ideas', '<span class="ct">6</span>')}
      ${nv('read', '📖', 'Read later', '<span class="ct">12</span>')}
      <div class="sp"></div>
      ${nv('find', '🔍', 'Find a note', '<span class="k">Ctrl K</span>')}
      ${nv('settings', '⚙', 'Settings')}
      <div class="hint" style="margin-top:10px">Quick capture from any app:<br><span class="kbd">Win</span> <span class="kbd">Shift</span> <span class="kbd">Space</span><br>Closing the window keeps it in the tray.</div>
    </div>
    <div class="main">${main}</div>
  </div>`;
});
