import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer, request as upstream } from 'node:http';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';

const password = randomBytes(24).toString('hex');
const jar = fileURLToPath(new URL('../../service/target/service.jar', import.meta.url));
let app, browser, proxy, origin;

before(async () => {
  app = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java', [
    '-jar', jar, '--spring.profiles.active=local', '--server.port=0'
  ], {
    env: {...process.env, SECRETCHAT_ADMIN_USERNAME:'browser-test', SECRETCHAT_ADMIN_PASSWORD:password},
    stdio:['ignore', 'pipe', 'inherit']
  });
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out starting test server')), 45000);
    app.once('error', reject);
    app.once('exit', code => { clearTimeout(timer); reject(new Error('Server exited: ' + code)); });
    createInterface({input:app.stdout}).on('line', line => {
      const matched = line.match(/Tomcat started on port (\d+)/i);
      if (matched) { clearTimeout(timer); resolve(Number(matched[1])); }
    });
  });
  // Same path rewrite as the proposed Nginx location, including cookies and redirects.
  proxy = createServer((req, res) => {
    if (!req.url.startsWith('/secretchat/')) { res.writeHead(404).end(); return; }
    const forward = upstream({hostname:'127.0.0.1', port, path:req.url.slice('/secretchat'.length),
      method:req.method, headers:req.headers}, reply => {
      res.writeHead(reply.statusCode, reply.headers);
      reply.pipe(res);
    });
    forward.on('error', () => res.writeHead(502).end());
    req.pipe(forward);
  });
  await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
  origin = 'http://127.0.0.1:' + proxy.address().port + '/secretchat';
  browser = await chromium.launch(process.env.CHROME_EXECUTABLE
    ? {executablePath:process.env.CHROME_EXECUTABLE} : {channel:'chrome'});
});

after(async () => {
  await browser?.close();
  if (proxy) await new Promise(resolve => proxy.close(resolve));
  app?.kill();
});

test('visitor signs in under the deployment prefix, refreshes live metrics, and signs out', async t => {
  const page = await browser.newPage();
  t.after(() => page.close());
  await page.goto(origin + '/console/');
  await expect(page).toHaveURL(origin + '/console/login.html');
  await page.getByLabel('管理员账号').fill('browser-test');
  await page.getByLabel('密码', {exact:true}).fill('incorrect');
  await page.getByRole('button', {name:'登录'}).click();
  await expect(page.getByRole('alert')).toContainText('账号或密码错误');
  await page.getByLabel('密码', {exact:true}).fill(password);
  await page.getByRole('button', {name:'登录'}).click();
  await expect(page.getByRole('heading', {name:'队列概览'})).toBeVisible();
  await expect(page.locator('#pending')).toHaveText('0');
  await expect(page.locator('#wait')).toHaveText('—');

  await page.route('**/admin-api/queue', route => route.fulfill({status:503}));
  await page.getByRole('button', {name:'刷新'}).click();
  await expect(page.getByRole('alert')).toContainText('加载失败');
  await page.unroute('**/admin-api/queue');
  const fresh = page.waitForResponse(r => r.url().endsWith('/admin-api/queue') && r.status() === 200);
  await page.getByRole('button', {name:'刷新'}).click();
  const data = await (await fresh).json();
  assert.deepEqual(data, {pendingCount:0, oldestWaitSeconds:null});
  await expect(page.locator('#pending')).toHaveText('0');
  await page.getByRole('button', {name:'退出登录'}).click();
  await expect(page).toHaveURL(origin + '/console/login.html');
  assert.equal((await page.request.get(origin + '/admin-api/queue')).status(), 401);
});

test('page returns to login on session expiry', async t => {
  const page = await browser.newPage();
  t.after(() => page.close());
  await page.goto(origin + '/console/login.html');
  await page.getByLabel('管理员账号').fill('browser-test');
  await page.getByLabel('密码', {exact:true}).fill(password);
  await page.getByRole('button', {name:'登录'}).click();
  await expect(page.locator('#pending')).toHaveText('0');
  await page.context().clearCookies();
  await page.getByRole('button', {name:'刷新'}).click();
  await expect(page).toHaveURL(origin + '/console/login.html');
});
