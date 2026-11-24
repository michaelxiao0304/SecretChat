import { submitForm } from './client.js';

const form = document.querySelector('#login');
const alert = document.querySelector('[role=alert]');
form.addEventListener('submit', async event => {
  event.preventDefault();
  const button = form.querySelector('button');
  button.disabled = true;
  alert.hidden = true;
  try {
    await submitForm('login', new FormData(form));
    location.replace('./');
  } catch (error) {
    alert.textContent = error.status === 401 ? '账号或密码错误，请重试。' : '暂时无法登录，请重试。';
    alert.hidden = false;
  } finally {
    button.disabled = false;
  }
});
