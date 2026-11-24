import { change, failureMessage, request } from './client.js';
import { decryptReport } from './report-crypto.js';

const el = id => document.getElementById(id);
const write = (id, value) => { el(id).textContent = value; };
const displayTime = millis => new Date(millis).toLocaleString('zh-CN', {hour12:false});
const nav = [...el('views').querySelectorAll('button')];
let view = 'overview';
let ticket;
let reviewEpoch = 0;
let viewEpoch = 0;

function failure(error, message) {
  if (error.status === 401) return location.replace('login.html');
  write('ops-error', failureMessage(message, error));
  el('ops-error').hidden = false;
}

function clearTicket() {
  ++reviewEpoch;
  ticket = undefined;
  el('ticket').hidden = true;
  el('private-key').value = '';
  for (const field of ['material','ticket-id','reporter','reported','key-id','review-feedback']) write(field, '');
}

// Async actions update only the view that initiated them.
async function run(action, message) {
  const epoch = viewEpoch;
  el('ops-error').hidden = true;
  const current = () => epoch === viewEpoch;
  try { await action(current); }
  catch (error) { if (current()) failure(error, message); }
}

async function providers(current) {
  for (const channel of ['email','push']) {
    write(channel + '-status','—');
    write(channel + '-failures','—');
  }
  write('registration-mode','');
  const data = await request('status');
  if (!current()) return;
  for (const channel of ['email','push']) {
    write(channel + '-status', data[channel].configured ? '已配置' : '未配置');
    write(channel + '-failures', data[channel].failures24h.toLocaleString('zh-CN'));
  }
  write('registration-mode', data.registrationMode === 'INVITE_ONLY_UNVERIFIED_EMAIL'
    ? '开发注册模式：邀请码 + 未验证邮箱' : '注册模式：邀请码 + 邮箱验证码');
}

function cells(body, values) {
  const row = document.createElement('tr');
  for (const value of values) {
    const cell = document.createElement('td');
    cell.append(value);
    row.append(cell);
  }
  body.append(row);
}

async function openTicket(id) {
  clearTicket();
  const epoch = reviewEpoch;
  await run(async current => {
    const data = await request('reports/' + encodeURIComponent(id));
    if (!current() || epoch !== reviewEpoch) return;
    ticket = data;
    write('ticket-id', id);
    write('reporter', data.reporterAccountId);
    write('reported', data.reportedAccountId);
    write('key-id', data.keyId);
    el('close-ticket').disabled = data.closedMs !== null;
    el('ticket').hidden = false;
    el('ticket').scrollIntoView({block:'nearest'});
  }, '读取工单失败，请重试。');
}

function reportRow(body, row) {
  const link = document.createElement('button');
  link.type = 'button';
  link.className = 'ticket-link';
  link.textContent = row.id;
  link.addEventListener('click', () => openTicket(row.id));
  cells(body,[link,displayTime(row.createdMs),row.closedMs === null ? '待审核' : '已结案']);
}

const actions = {REPORT_LIST:'查看工单列表',REPORT_READ:'查看举报材料',REPORT_CLOSE:'工单结案',
  AUDIT_LIST:'查看操作记录','invite.limit':'修改邀请名额','invite.bootstrap':'签发首个邀请码'};

function auditRow(body, row) {
  cells(body,[displayTime(row.occurredMs),row.operator,actions[row.action] || row.action,row.subject]);
}

async function loadList(section, before) {
  const reports = section === 'reports';
  if (reports) clearTicket();
  const body = el(reports ? 'report-rows' : 'audit-rows');
  const next = el(section + '-next');
  next.hidden = true;
  body.replaceChildren();
  if (reports) el('reports-empty').hidden = true;
  await run(async current => {
    const rows = await request(section + (before ? '?before=' + encodeURIComponent(before) : ''));
    if (!current()) return;
    const render = reports ? reportRow : auditRow;
    rows.forEach(row => render(body,row));
    next.hidden = rows.length < 50;
    next.dataset.before = rows.at(-1)?.seq ?? '';
    if (reports) el('reports-empty').hidden = rows.length !== 0;
  }, '列表加载失败，请重新更新。');
}

function showSettings(data) {
  el('quota').value = data.limit;
  el('bootstrap').disabled = !data.bootstrapAvailable;
  write('bootstrap', data.bootstrapAvailable ? '生成首个邀请码' : '首个邀请码已兑换');
}

async function loadSettings(current) {
  const settings = await request('invites/settings');
  if (current()) showSettings(settings);
}

function select(name) {
  ++viewEpoch;
  view = name;
  clearTicket();
  write('bootstrap-code','');
  write('invite-feedback','');
  for (const button of nav) {
    const selected = button.dataset.view === name;
    button.setAttribute('aria-pressed',String(selected));
    el(button.dataset.view).hidden = !selected;
  }
  if (name === 'overview') return run(providers,'邮件与推送状态加载失败，请刷新。');
  if (name === 'invites') return run(loadSettings,'邀请设置加载失败，请重试。');
  return loadList(name);
}

async function identify() {
  try {
    const user = await request('me');
    const admin = user.roles.includes('ADMIN');
    const visible = {overview:true,reports:admin || user.roles.includes('REVIEWER'),invites:admin,audit:admin};
    nav.forEach(button => { button.hidden = !visible[button.dataset.view]; });
    write('operator',user.username);
  } catch (error) {
    failure(error,'账号权限加载失败，请刷新。');
  }
}

el('decrypt').addEventListener('click', async () => {
  const epoch = reviewEpoch;
  write('material','');
  const file = el('private-key').files[0];
  el('private-key').value = '';
  if (!file || file.size > 16384) return write('review-feedback','请选择大小不超过 16 KiB 的 PKCS#8 私钥。');
  write('review-feedback','正在本机解密…');
  try {
    const clear = await decryptReport(ticket, await file.text());
    if (epoch !== reviewEpoch) return;
    write('material',JSON.stringify(clear,null,2));
    write('review-feedback','已在本机解密。');
  } catch {
    if (epoch === reviewEpoch) write('review-feedback','解密失败，请确认使用受信任的 HTTPS、对应指纹的 PKCS#8 私钥以及完整的举报材料。');
  }
});

el('close-ticket').addEventListener('click', () => {
  const id = ticket.id;
  el('close-ticket').disabled = true;
  return run(async current => {
    try {
      await change('reports/' + id + '/close','POST');
      if (current()) await loadList('reports');
    } finally {
      if (ticket?.id === id) el('close-ticket').disabled = ticket.closedMs !== null;
    }
  },'工单结案失败，请重试。');
});

el('quota-form').addEventListener('submit', event => {
  event.preventDefault();
  run(async current => {
    await change('invites/settings','PUT',{limit:Number(el('quota').value)});
    if (current()) write('invite-feedback','邀请名额已保存。');
  },'邀请名额保存失败，请重试。');
});

el('bootstrap').addEventListener('click', () => {
  el('bootstrap').disabled = true;
  write('bootstrap-code','');
  return run(async current => {
    try {
      const issued = await change('invites','POST');
      if (current()) {
        write('bootstrap-code',issued.code);
        write('bootstrap','重新生成并作废旧码');
      }
    } finally {
      if (current()) el('bootstrap').disabled = false;
    }
  },'邀请码生成失败，请重新打开邀请管理核对状态。');
});

nav.forEach(button => button.addEventListener('click', () => select(button.dataset.view)));
for (const section of ['reports','audit']) {
  el(section + '-refresh').addEventListener('click', () => select(section));
  const next = el(section + '-next');
  next.addEventListener('click', () => { ++viewEpoch; loadList(section,next.dataset.before); });
}
el('refresh').addEventListener('click', () => { identify(); select('overview'); });
function clearSensitiveView() {
  ++viewEpoch;
  clearTicket();
  write('bootstrap-code','');
}
el('logout').addEventListener('click', clearSensitiveView);
window.addEventListener('pagehide', clearSensitiveView);
identify();
select(view);
