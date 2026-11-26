# 运营控制台和举报 API

通过部署前缀下的 `console/` 登录。公网使用可信 HTTPS，包括直接按 IP 访问时对该 IP 有效且受本机信任的证书。管理员会话与设备 Bearer 凭据独立。页面资源、请求和 Cookie 适用于同源路径前缀，例如 `/secretchat/`。

| 角色 | 可用功能 |
|---|---|
| OPS | 队列深度、最老等待时间、注册验证模式、邮件与 APNs 配置状态及近 24 小时失败次数 |
| REVIEWER | 运行概览、举报列表、工单材料本机解密、结案 |
| ADMIN | 全部以上功能、邀请名额、首个邀请码和操作记录 |

`SECRETCHAT_ADMIN_USERNAME/PASSWORD` 配置管理员。可选的审核员和运维账号分别通过 `SECRETCHAT_REVIEWER_USERNAME/PASSWORD` 和 `SECRETCHAT_OPS_USERNAME/PASSWORD` 设置。三个用户名各不相同。

## 设备提交举报

设备通过 `Authorization: Bearer <device-token>` 访问两个接口。提交前在端侧让举报人选择并预览消息和账号信息，确认后只加密本次选中的材料。材料为 UTF-8 JSON，推荐形如 `{"messages":[{"text":"…"}]}`。内容是举报人的陈述，审核界面以纯文本显示。

1. `GET /api/reports/key` 返回 `{version:1,keyId,publicKey}`。服务端从 `SECRETCHAT_REPORT_PUBLIC_KEY` 读取 RSA 公钥的 SPKI DER 标准 Base64，支持 2048、3072、4096 位密钥。指纹 `keyId` 为 DER 字节的 SHA-256 小写十六进制。缺少配置时返回 503。
2. 生成小写规范 UUID 作为工单 `id`。每份材料使用独立随机 32 字节 AES 密钥和独立随机 12 字节 IV。用 AES-256-GCM 加密材料，128 位认证标签附在密文末尾。AAD 是以下五行以 LF 连接后的 UTF-8 字节，末行没有换行。

```text
SecretChat/report/v1
<id>
<reporterAccountId>
<reportedAccountId>
<keyId>
```

3. 用运营公钥以 RSA-OAEP 加密 AES 密钥，摘要与 MGF1 都采用 SHA-256，label 为空。将结果作为 `wrappedKey`。所有二进制字段使用带标准填充的 Base64。
4. `POST /api/reports` 使用 `Content-Type: application/json`，提交 `{id,reportedAccountId,version:1,keyId,wrappedKey,iv,ciphertext}`。服务端在入库事务中验证设备仍有效，并校验双方的联系人关系。可举报已拉黑的联系人；其他账号返回 404。服务端从当前设备取得举报人账号。工单号和被举报账号都使用小写规范 UUID。密文含认证标签共 17 至 49,152 字节，整个 JSON 最多 72 KiB。未知字段、格式错误、自我举报返回 400。超出请求体上限返回 413。原样重传返回相同的 `200 {"id":"…"}`。工单 ID 已被另一份材料占用或收件密钥已更换时返回 409。

审核员在可信浏览器选择对应指纹的未加口令 PKCS#8 PEM 私钥，通过 WebCrypto 在本机解密。控制台清空文件输入，只用内存显示当次结果。切换工单、结案、离开页面或退出时清除显示。服务端只需 SPKI 公钥；私钥在审核工作站保管，轮换后保留解密尚未清理的旧工单所需的私钥。

## 管理 API

所有写操作都先读取 `GET /admin-api/session`，把返回的 `header` 和 `token` 作为请求头。未登录返回 401，权限不足或 CSRF 验证失败返回 403。列表按最新记录优先每页最多 50 项，下一页传入上一页末项的 `seq` 作为 `before`。

| 接口 | 响应或作用 |
|---|---|
| GET /admin-api/me | 当前用户名和角色名数组 |
| GET /admin-api/queue | 待投递信封数、最老等待秒数 |
| GET /admin-api/status | registrationMode、email 和 push 各自的 configured、failures24h |
| GET /admin-api/reports?before=… | 工单号、双方账号、提交与结案毫秒时间，列表只含元数据 |
| GET /admin-api/reports/{id} | 该工单的版本、双方账号、密文、IV、封装密钥、指纹与结案时间 |
| POST /admin-api/reports/{id}/close | 204；重复请求保持首次结案时间 |
| GET /admin-api/invites/settings | limit 和 bootstrapAvailable |
| PUT /admin-api/invites/settings | JSON `{"limit":7}`，范围 0 至 100 |
| POST /admin-api/invites | 生成首个邀请码，返回 id、code；重新生成会使旧的未兑换凭据失效，首次兑换后停止签发 |
| GET /admin-api/audit?before=… | 操作员、动作、工单号或相关标识及毫秒时间 |

`sc_ops` 独立存放举报和操作审计。举报列表、读取密文、结案、邀请名额变更、签发初始邀请以及查看审计列表都会留下记录。密文只在工单详情接口返回。结案满 30 天后工单立即从查询中排除；每小时清理到期工单和 30 天前的审计记录。未结案工单持续保留。浏览操作写入审计失败时不返回工单材料。运行指标按聚合值读取。
