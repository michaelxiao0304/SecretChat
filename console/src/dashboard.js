import { failureMessage, formatWait, request, submitForm } from './client.js';

const refresh = document.querySelector('#refresh');
const logout = document.querySelector('#logout');
const pending = document.querySelector('#pending');
const wait = document.querySelector('#wait');
const status = document.querySelector('#status');
const alert = document.querySelector('[role=alert]');

function showError(message, error) {
  alert.textContent = failureMessage(message, error);
  alert.hidden = false;
}

async function loadQueue() {
  refresh.disabled = true;
  alert.hidden = true;
  status.textContent = '正在加载…';
  try {
    const snapshot = await request('queue');
    pending.textContent = snapshot.pendingCount.toLocaleString('zh-CN');
    wait.textContent = formatWait(snapshot.oldestWaitSeconds);
    status.textContent = '更新于 ' + new Date().toLocaleTimeString('zh-CN');
  } catch (error) {
    if (error.status === 401) return location.replace('login.html');
    pending.textContent = '—';
    wait.textContent = '—';
    status.textContent = '';
    showError('队列指标加载失败，请稍后刷新。', error);
  } finally {
    refresh.disabled = false;
  }
}

logout.addEventListener('click', async () => {
  logout.disabled = true;
  try {
    await submitForm('logout', {});
    location.replace('login.html');
  } catch (error) {
    showError('退出失败，请重试。', error);
  } finally {
    logout.disabled = false;
  }
});

refresh.addEventListener('click', loadQueue);
loadQueue();
