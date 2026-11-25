const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const state = {
  view: 'boot', games: [], selected: null, booted: false,
  focusIndex: 0, runs: Number(localStorage.getItem('j2me_runs') || 0),
  padPrev: {}, padFocus: 0, lastNav: 0,
};

const softkeys = { left: $('#skLeft'), right: $('#skRight') };
const toast = $('#toast');

function showToast(msg) {
  toast.textContent = msg;
  toast.classList.add('show');
  clearTimeout(showToast.t);
  showToast.t = setTimeout(() => toast.classList.remove('show'), 2600);
}

/* ---------- ЗАГРУЗКА ---------- */
const bootLines = [
  'Загрузка ядра CLDC...', 'Инициализация MIDP 2.0...',
  'Монтирование виртуальной файловой системы...', 'Поиск MIDlet-пакетов...',
  'Готово. Добро пожаловать.',
];
function boot() {
  const bar = $('#bootBar'), line = $('#bootLine');
  let p = 0;
  const timer = setInterval(() => {
    p += 4 + Math.random() * 7;
    if (p >= 100) {
      p = 100; clearInterval(timer);
      setTimeout(() => { state.booted = true; setView('menu'); }, 350);
    }
    bar.style.width = p + '%';
    line.textContent = bootLines[Math.min(bootLines.length - 1, Math.floor(p / 22))];
  }, 75);
  state.skipBoot = () => { clearInterval(timer); state.booted = true; setView('menu'); };
}
window.addEventListener('keydown', e => { if (!state.booted && state.skipBoot) state.skipBoot(); });
window.addEventListener('mousedown', () => { if (!state.booted && state.skipBoot) state.skipBoot(); });

/* ---------- НАВИГАЦИЯ ---------- */
const viewNames = {
  menu: 'МЕНЮ', games: 'СПИСОК', detail: 'ПУСК', profiles: 'ВЫБОР',
  records: 'ОБНОВИТЬ', settings: 'СБРОС', about: 'НАЗАД', console: 'ОТПРАВИТЬ',
};
function setView(name) {
  if (!state.booted) return;
  state.view = name;
  $$('.view').forEach(v => v.classList.toggle('active', v.id === 'view-' + name));
  softkeys.left.textContent = viewNames[name] || 'МЕНЮ';
  softkeys.right.textContent = name === 'menu' ? 'ВЫБОР' : 'НАЗАД';
  state.focusIndex = 0;
  if (name === 'games') renderGames();
  if (name === 'records') refreshStatus();
  if (name === 'console') setTimeout(() => $('#termInput').focus(), 50);
  updateFocus();
}
function focusables() {
  return $$('.view.active .tile, .view.active .game-row, .view.active .launch, .view.active .sk');
}
function updateFocus() {
  const els = focusables();
  els.forEach(e => e.classList.remove('focus'));
  if (!els.length) return;
  state.focusIndex = Math.max(0, Math.min(state.focusIndex, els.length - 1));
  els[state.focusIndex].classList.add('focus');
  els[state.focusIndex].scrollIntoView({ block: 'nearest' });
}
function moveFocus(delta) {
  const els = focusables();
  if (!els.length) return;
  state.focusIndex = (state.focusIndex + delta + els.length) % els.length;
  updateFocus();
}
function activateFocused() {
  const el = focusables()[state.focusIndex];
  if (el) el.click();
}
function goBack() {
  if (state.view === 'detail') setView('games');
  else if (state.view !== 'menu') setView('menu');
}

$$('[data-view]').forEach(el => el.addEventListener('click', () => setView(el.dataset.view)));
$('#skLeft').addEventListener('click', () => {
  if (state.view === 'records') refreshStatus();
  else if (state.view === 'settings') resetSettings();
  else if (state.view === 'console') runTermCommand();
  else setView('menu');
});
$('#skRight').addEventListener('click', () => {
  if (state.view === 'menu') activateFocused();
  else if (state.view === 'console') runTermCommand();
  else goBack();
});

/* ---------- ИГРЫ ---------- */
async function loadGames() {
  try {
    const r = await fetch('/api/games');
    const data = await r.json();
    state.games = data.games || [];
    $('#gamesCount').textContent = state.games.length;
    $('#statGames').textContent = state.games.length;
    $('#menuCount').textContent = state.games.length + ' MIDlet';
    renderGames();
  } catch (e) { showToast('Не удалось получить список игр'); }
}
function thumbClass(id) {
  return ['snake', 'bricks', 'starfighter'].includes(id) ? id : 'default';
}
function thumbIcon(id) {
  return { snake: '🐍', bricks: '🧱', starfighter: '🚀' }[id] || '📦';
}
function renderGames() {
  const box = $('#gameList');
  box.innerHTML = '';
  if (!state.games.length) { box.innerHTML = '<div class="note">Игры не найдены</div>'; return; }
  state.games.forEach(g => {
    const b = document.createElement('button');
    b.className = 'game-row';
    b.dataset.game = g.id;
    b.innerHTML = `<span class="thumb ${thumbClass(g.id)}">${thumbIcon(g.id)}</span>
      <span class="grow"><b>${g.name}</b><small>${(g.size / 1024).toFixed(1)} КБ · ${g.desc}</small></span>
      <span class="play-tag">▶</span>`;
    b.addEventListener('click', () => openDetail(g));
    box.appendChild(b);
  });
  updateFocus();
}
function openDetail(g) {
  state.selected = g;
  $('#detailName').textContent = g.name;
  $('#detailDesc').textContent = g.desc;
  $('#detailSize').textContent = (g.size / 1024).toFixed(1) + ' КБ';
  $('#detailIcon').textContent = thumbIcon(g.id);
  $('#detailCover').className = 'cover ' + thumbClass(g.id);
  $('#selPhone').value = g.id === 'snake' ? 'Nokia' : 'Standard';
  $('#selW').value = 240; $('#selH').value = 320; $('#selScale').value = 2;
  setView('detail');
}

$('#launchBtn').addEventListener('click', async () => {
  if (!state.selected) return;
  const btn = $('#launchBtn');
  btn.disabled = true; btn.textContent = 'ЗАПУСК...';
  try {
    const data = {
      id: state.selected.id,
      engine: $('#selEngine').value,
      phone: $('#selPhone').value,
      width: Number($('#selW').value), height: Number($('#selH').value),
      scale: Number($('#selScale').value),
    };
    if ($('#optFull').checked) data.scale = 3;
    const r = await fetch('/api/launch', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
    });
    const res = await r.json();
    if (res.ok) {
      state.runs++;
      localStorage.setItem('j2me_runs', state.runs);
      const eng = { 'own-shell': 'прошивка OwnJ2ME', own: 'OwnJ2ME', freej2me: 'FreeJ2ME' }[res.engine] || res.engine;
      showToast(`▶ ${res.name} · ${eng} (PID ${res.pid})`);
    } else showToast(res.error || 'Ошибка запуска');
  } catch (e) { showToast('Ошибка связи с лаунчером'); }
  btn.disabled = false; btn.textContent = '▶ ЗАПУСТИТЬ';
});

/* ---------- СТАТУС ---------- */
async function refreshStatus() {
  $('#statRuns').textContent = state.runs;
  try {
    const r = await fetch('/api/status');
    const data = await r.json();
    const list = data.running || [];
    $('#statNow').textContent = list.length;
    const box = $('#runningList');
    if (!list.length) { box.innerHTML = '<div>Пусто</div>'; return; }
    box.innerHTML = '';
    list.forEach(p => {
      const d = document.createElement('div');
      d.className = 'run-item';
      d.innerHTML = `<span>${p.game} <small>#${p.pid}</small></span>`;
      const btn = document.createElement('button');
      btn.textContent = 'СТОП';
      btn.onclick = async () => {
        await fetch('/api/stop', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pid: p.pid }) });
        refreshStatus();
      };
      d.appendChild(btn); box.appendChild(d);
    });
  } catch (e) { $('#runningList').textContent = 'Нет связи с лаунчером'; }
}
setInterval(() => { if (state.view === 'records') refreshStatus(); }, 4000);

/* ---------- НАСТРОЙКИ ---------- */
function resetSettings() {
  localStorage.removeItem('j2me_runs');
  state.runs = 0;
  $('#optSound').checked = true; $('#optFull').checked = false; $('#optPad').checked = true;
  showToast('Настройки сброшены');
}
$('#clearLog').addEventListener('click', async () => {
  await fetch('/api/clearlog', { method: 'POST' }).catch(() => {});
  showToast('Логи очищены');
});

/* ---------- ТЕРМИНАЛ ---------- */
function termPrint(text) {
  const t = $('#term'); t.textContent += text + '\n'; t.scrollTop = t.scrollHeight;
}
function runTermCommand() {
  const input = $('#termInput'), cmd = input.value.trim();
  if (!cmd) return;
  input.value = '';
  termPrint('$ ' + cmd);
  const [name, ...args] = cmd.split(/\s+/);
  switch (name.toLowerCase()) {
    case 'help':
      termPrint('команды: ls, run <id>, games, stop <pid>, status, dumps, xemu, clear, echo'); break;
    case 'ls': case 'games':
      termPrint(state.games.map(g => `${g.id.padEnd(14)} ${g.size}B ${g.name}`).join('\n') || '(пусто)'); break;
    case 'run': {
      const g = state.games.find(x => x.id === args[0]);
      if (!g) { termPrint('нет такой игры: ' + (args[0] || '')); break; }
      openDetail(g); termPrint('открыто меню запуска: ' + g.name); break;
    }
    case 'clear': $('#term').textContent = ''; break;
    case 'echo': termPrint(args.join(' ')); break;
    case 'dumps':
      termPrint('ROM-образы телефонов проприетарны.\nСними дамп со СВОЕГО устройства.\nДля J2ME они не нужны — достаточно JAR.'); break;
    case 'xemu':
      termPrint('Xemu = оригинальный Xbox.\nXbox 360 = Xenia (без BIOS-дампов).'); break;
    case 'status': refreshStatus(); termPrint('запущенных процессов: ' + $('#statNow').textContent); break;
    case 'stop':
      if (args[0]) fetch('/api/stop', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pid: Number(args[0]) }) }).then(() => termPrint('-stop ' + args[0]));
      break;
    default: termPrint('неизвестная команда: ' + name);
  }
}
$('#termInput').addEventListener('keydown', e => { if (e.key === 'Enter') { e.stopPropagation(); runTermCommand(); } });

/* ---------- КЛАВИАТУРА ---------- */
window.addEventListener('keydown', e => {
  if (!state.booted) return;
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
  const k = e.key;
  if (k === 'Escape') { goBack(); e.preventDefault(); }
  else if (k === 'ArrowDown' || k === 's') { moveFocus(1); e.preventDefault(); }
  else if (k === 'ArrowUp' || k === 'w') { moveFocus(-1); e.preventDefault(); }
  else if (k === 'ArrowRight' || k === 'd') { moveFocus(1); e.preventDefault(); }
  else if (k === 'ArrowLeft' || k === 'a') { moveFocus(-1); e.preventDefault(); }
  else if (k === 'Enter' || k === ' ') { activateFocused(); e.preventDefault(); }
});

/* ---------- ГЕЙМПАД ---------- */
function pollPad() {
  if (state.booted && $('#optPad').checked && navigator.getGamepads) {
    const pads = navigator.getGamepads();
    const pad = [...pads].find(p => p);
    if (pad) {
      const now = performance.now();
      const ax = pad.axes[0] || 0, ay = pad.axes[1] || 0;
      let dir = 0;
      if (pad.buttons[14]?.pressed || ax < -0.5) dir = 1;
      if (pad.buttons[15]?.pressed || ax > 0.5) dir = -1;
      if (pad.buttons[12]?.pressed || ay < -0.5) dir = 1;
      if (pad.buttons[13]?.pressed || ay > 0.5) dir = -1;
      if (dir && now - state.lastNav > 180) { moveFocus(dir); state.lastNav = now; }
      const a = !!pad.buttons[0]?.pressed, b = !!pad.buttons[1]?.pressed;
      if (a && !state.padPrev.a) activateFocused();
      if (b && !state.padPrev.b) goBack();
      state.padPrev = { a, b };
    }
  }
  requestAnimationFrame(pollPad);
}

/* ---------- ЧАСЫ ---------- */
setInterval(() => {
  $('#clock').textContent = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}, 1000);
$('#clock').textContent = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });

async function loadFirmwareInfo() {
  try {
    const r = await fetch('/api/firmware');
    const d = await r.json();
    $('#firmwareStatus').textContent = d.dir
      ? `Прошивка подключена: ${d.dir} (${d.images} образов). Иконки меню читаются из неё напрямую.`
      : 'Прошивка не найдена. Положи образы в ~/xbox-emulator/firmware/ или укажи J2ME_FIRMWARE.';
  } catch (e) { $('#firmwareStatus').textContent = 'Не удалось получить статус прошивки'; }
}

boot();
loadGames();
loadFirmwareInfo();
pollPad();
