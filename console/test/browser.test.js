import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { encryptedFixture } from './report-fixture.js';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes, createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer, request as upstream } from 'node:http';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';

const password = randomBytes(24).toString('hex');
const jar = fileURLToPath(new URL('../../service/target/service.jar', import.meta.url));
const fixture = encryptedFixture();
const token = randomBytes(32).toString('base64url');
let app, browser, proxy, origin, temp;

before(async () => {
  temp = await mkdtemp(join(tmpdir(), 'secretchat-console-test-'));
  const sql = join(temp,'device.sql');
  const account = fixture.report.reporterAccountId;
  const peer = fixture.report.reportedAccountId;
  const [first,second] = [account,peer].sort();
  const digest = createHash('sha256').update(token).digest('hex');
  await writeFile(sql, `INSERT INTO sc_directory.accounts(account_id,email) VALUES ('${account}','browser@example.test'),('${peer}','peer@example.test');
INSERT INTO sc_directory.contacts(first_id,second_id) VALUES ('${first}','${second}');
INSERT INTO sc_directory.devices(device_id,account_id,token_hash,identity_key,signed_prekey) VALUES ('${crypto.randomUUID()}','${account}','${digest}',X'00',X'01');`);
  app = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java', [
    '-jar', jar, '--spring.profiles.active=local', '--server.port=0', '--spring.sql.init.data-locations=file:' + sql,
    '--secretchat.report.public-key=' + fixture.publicKey
  ], {
    env: {...process.env, SECRETCHAT_ADMIN_USERNAME:'browser-test', SECRETCHAT_ADMIN_PASSWORD:password,
      SECRETCHAT_REVIEWER_USERNAME:'reviewer', SECRETCHAT_REVIEWER_PASSWORD:password,
      SECRETCHAT_OPS_USERNAME:'metrics', SECRETCHAT_OPS_PASSWORD:password},
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
  if (temp) await rm(temp, {recursive:true,force:true});
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

async function signIn(page, username) {
  await page.goto(origin + '/console/login.html');
  await page.getByLabel('管理员账号').fill(username);
  await page.getByLabel('密码', {exact:true}).fill(password);
  await page.getByRole('button', {name:'登录'}).click();
  await expect(page.locator('#pending')).toHaveText('0');
}

test('device submits ciphertext and reviewer decrypts locally, closes the ticket and leaves an audit trail', async t => {
  const report = fixture.report;
  const {reporterAccountId, ...submission} = report;
  const headers = {Authorization:'Bearer ' + token, 'Content-Type':'application/json'};
  const key = await fetch(origin + '/api/reports/key', {headers});
  assert.equal(key.status, 200);
  assert.equal((await key.json()).keyId, report.keyId);
  const post = body => fetch(origin + '/api/reports',{method:'POST',headers,body:JSON.stringify(body)});
  assert.equal((await post({...submission, messages:fixture.evidence.messages})).status, 400);
  assert.equal((await post({...submission, version:2})).status, 400);
  assert.equal((await post({...submission, iv:'AA=='})).status, 400);
  assert.equal((await post({...submission, keyId:'0'.repeat(64)})).status, 409);
  assert.equal((await post(submission)).status, 200);
  assert.equal((await post(submission)).status, 200);
  assert.equal((await post({...submission,ciphertext:Buffer.alloc(32).toString('base64')})).status, 409);

  const page = await browser.newPage();
  t.after(() => page.close());
  const requests = [];
  page.on('request', request => requests.push(request.url() + ' ' + (request.postData() || '')));
  await signIn(page, 'reviewer');
  assert.equal((await page.request.get(origin + '/admin-api/audit')).status(),403);
  await page.getByRole('button',{name:'举报工单',exact:true}).click();
  await expect(page.getByRole('button',{name:report.id})).toHaveCount(1);
  await page.getByRole('button',{name:report.id}).click();
  await expect(page.locator('#material')).toBeEmpty();
  await expect(page.locator('#key-id')).toHaveText(report.keyId);
  await page.getByLabel('本机审核私钥').setInputFiles({name:'review.pem',mimeType:'text/plain',buffer:Buffer.from(fixture.pem)});
  await page.getByRole('button',{name:'在本机解密'}).click();
  await expect(page.locator('#material')).toContainText(fixture.evidence.messages[0].text);
  assert.equal((await page.request.post(origin + '/admin-api/reports/' + report.id + '/close')).status(),403);
  await page.getByRole('button',{name:'结案',exact:true}).click();
  await expect(page.getByText('已结案',{exact:true}).first()).toBeVisible();
  await expect(page.locator('#material')).toBeEmpty();
  assert.ok(requests.every(text => !text.includes('PRIVATE KEY') && !text.includes(fixture.evidence.messages[0].text)));
  assert.deepEqual(await page.evaluate(()=>[localStorage.length,sessionStorage.length]),[0,0]);

  await signIn(page,'browser-test');
  await page.getByRole('button',{name:'操作记录',exact:true}).click();
  await expect(page.locator('#audit-rows')).toContainText('工单结案');
  await expect(page.locator('#audit-rows')).toContainText('查看举报材料');
  await expect(page.locator('#audit-rows')).toContainText('reviewer');
  await page.getByRole('button',{name:'邀请管理',exact:true}).click();
  await page.getByLabel('每个账号可持有的邀请码数量').fill('7');
  await page.getByRole('button',{name:'保存名额'}).click();
  await expect(page.locator('#invite-feedback')).toContainText('已保存');
  await page.getByRole('button',{name:'生成首个邀请码'}).click();
  await expect(page.locator('#bootstrap-code')).toHaveText(/[A-Za-z0-9_-]+/);
  await expect(page.getByRole('button',{name:'重新生成并作废旧码'})).toBeVisible();

  await signIn(page,'metrics');
  await expect(page.getByRole('button',{name:'举报工单'})).toHaveCount(0);
  assert.equal((await page.request.get(origin + '/admin-api/reports')).status(),403);
  assert.equal((await page.request.get(origin + '/admin-api/invites/settings')).status(),403);
  await expect(page.locator('#email-status')).toContainText('未配置');
  await expect(page.locator('#push-status')).toContainText('未配置');
});
