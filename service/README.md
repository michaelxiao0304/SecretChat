# 服务端与运营控制台

服务端把同仓库的 `console/src/` 打包进 JAR。管理员登录后可以查看队列、注册模式、供应商状态、管理邀请和处理举报。设备通过独立的 Bearer 令牌访问账号、联系人、公钥及信箱接口。

- [注册、设备换机、邀请与公钥目录](directory-api.md)
- [发送、补拉、收件确认、过期与 WebSocket](mailbox-api.md)
- [控制台权限、举报加密、审核与审计](operations-api.md)

## 本地启动

需要 JDK 17。在仓库根目录运行以下命令。输入的密码仅供本次启动使用；可通过 `SECRETCHAT_ADMIN_USERNAME` 修改默认用户名 `admin`。

```sh
cd service
./mvnw verify
read -rs SECRETCHAT_ADMIN_PASSWORD
export SECRETCHAT_ADMIN_PASSWORD
export SECRETCHAT_REGISTRATION_UNVERIFIED=true
java -jar target/service.jar --spring.profiles.active=local
```

打开 [本地运营控制台](http://127.0.0.1:8080/console/)。`local` 配置使用内存 H2，启动时建表，默认只监听回环地址。重新启动会清空本地数据。刷新按钮重新读取数据库，页面会显示错误并允许重试。退出登录会使服务端会话失效。

## MySQL 与反向代理

连接一个专用于此服务的 MySQL 实例或使用同实例上的独立库。建好默认队列库后，用迁移账号在该库依次执行 `src/main/resources/` 下的 `namespaces.sql`、`schema.sql`、`directory.sql`、`mailbox.sql`、`ops.sql`、`providers.sql`。默认配置由迁移账号建表，JAR 只运行 DML。应用账号需要队列库、`sc_directory`、`sc_ops` 的读写权限和 `sc_ledger.revocations` 的 INSERT 权限。队列和推送绑定应排除在备份、二进制日志、慢查询及请求日志之外。

| 变量 | 配置 |
|---|---|
| `SECRETCHAT_DB_URL`、`SECRETCHAT_DB_USERNAME`、`SECRETCHAT_DB_PASSWORD` | 本机 MySQL 队列库的 JDBC URL 和专用运行账号 |
| `SECRETCHAT_ADMIN_USERNAME`、`SECRETCHAT_ADMIN_PASSWORD` | 独立管理员账号，必须设置密码。可选的 REVIEWER 和 OPS 账号见运营 API 文档。 |
| `SECRETCHAT_REGISTRATION_UNVERIFIED` | 默认为 false。开发环境设置 true，从注册请求的 email 字段读取唯一测试标识。 |
| `SECRETCHAT_CHALLENGE_SECRET` | 至少 32 字节的随机密钥，用于验证码及限频桶的 HMAC。local 配置可自动生成。 |
| `SECRETCHAT_MAIL_API_KEY`、`SECRETCHAT_MAIL_FROM` | Resend API 密钥和已验证发件地址。 |
| `SECRETCHAT_APNS_KEY_FILE`、`SECRETCHAT_APNS_KEY_ID`、`SECRETCHAT_APNS_TEAM_ID`、`SECRETCHAT_APNS_TOPIC` | Apple .p8 文件路径、Key ID、Team ID、App Bundle ID。沙盒使用 `SECRETCHAT_APNS_SANDBOX=true`。 |
| `SECRETCHAT_REPORT_PUBLIC_KEY` | RSA 审核公钥的 SPKI DER Base64。审核用 PKCS#8 PEM 私钥保存在审核工作站。 |
| `SECRETCHAT_COOKIE_PATH`、`PORT` | 代理挂载时设 Cookie 路径为 `/secretchat/`。默认监听 `127.0.0.1:8080`。 |

共享 MySQL 开启 binlog 时，可由维护账号创建 `sc_ops.disable_app_binlog()`，过程定义为 `SQL SECURITY DEFINER SET SESSION sql_log_bin=0`，然后仅给应用账号授予该过程的 EXECUTE 权限。设置 `SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL='CALL sc_ops.disable_app_binlog()'` 后，每条池连接都会在使用前关闭自己的 binlog。可通过 `performance_schema.variables_by_thread` 核实应用连接的 `sql_log_bin` 均为 OFF。

Nginx 将整个 `/secretchat/` 去掉前缀转发到 Spring Boot，覆盖控制台、两组 API 和带升级头的 WebSocket。代理覆盖客户端传入的 `X-Real-IP` 为 `$remote_addr`，供验证码限频。控制台入口和主页由 Spring Security 校验；登录页及其必要静态资源允许匿名加载。正式配置使用 Secure、HttpOnly、SameSite=Strict 的独立会话 Cookie，经 HTTPS 访问。现有 demo 的 `/login` 使用自己的路由和账号。

| 应用内部接口 | 会话与结果 |
|---|---|
| `GET /admin-api/session` | 返回当前会话的 CSRF 请求头名称和令牌，允许登录前调用 |
| `POST /admin-api/login` | 表单字段为 `username` 和 `password`；带 CSRF 头，成功返回 204，账号或密码错误返回 401 |
| `GET /admin-api/queue` | 管理员可读，只返回两项汇总指标；未登录返回 401 |
| `POST /admin-api/logout` | 带当前 CSRF 头使会话失效，成功返回 204；缺少有效令牌返回 403 |

## 自动化验证

以下命令分别在对应目录运行。控制台测试需要 Node.js 20 或以上版本，本机 Chrome 或通过 `CHROME_EXECUTABLE` 指定的 Chromium。浏览器测试自行启动打包后的 JAR 和测试代理，使用随机端口和一次性测试密码。

```sh
# service/
./mvnw verify

# console/
npm ci
npm test
npm run test:browser
```

服务端单测、HTTP 与 WebSocket 测试覆盖注册开关、邀请并发兑换、账号恢复与撤销、一次性密钥、投递去重、拉黑、过期与确认、供应商错误、工单、权限和审计。默认使用内存 H2。针对 MySQL 的用例读取 `SECRETCHAT_TEST_DB_URL`、`SECRETCHAT_TEST_DB_USERNAME`、`SECRETCHAT_TEST_DB_PASSWORD`。请使用独立测试实例，默认库命名为 `secretchat_test`，另备 `sc_directory`、`sc_ledger`、`sc_ops`。测试会重建或清理测试数据。

控制台单测覆盖 API、登录、权限界面、举报材料解密与异常。Chrome 测试通过带 `/secretchat/` 路径前缀的真实 HTTP 服务验证登录、邀请管理、角色权限、工单本机解密及退出。

## 账号目录恢复

可选的账号目录备份仅包含 `sc_directory`，最长保留 7 天。撤销记录独立保管，覆盖全部可恢复备份。恢复时先停项目服务并隔离所有写入者。需要截至停服时完整、独立保管的撤销记录和最终校验值。缺少可信最终记录时，应保持停止并放弃旧备份恢复。实例级快照、DB 全量备份和日志归档须单独核实其覆盖范围。

1. 在可信的最终撤销库上执行 `python3 ops/reconcile-directory.py --defaults-file=/path/to/maintenance.cnf --checkpoint`，独立保存输出的 SHA-256。已丢失或截断的撤销库无法用于生成可信校验值。
2. 在隔离环境仅恢复账号目录，使用完整撤销库及全新的空队列库。保持所有投递和登录进程停止。
3. 在恢复目标执行 `python3 ops/reconcile-directory.py --defaults-file=/path/to/maintenance.cnf --replay <最终校验值>`。校验通过后，脚本在同一事务清理已注销账号、旧设备、挑战和待兑换邀请，并使恢复的一次性预密钥失效。可重复执行。旧邀请需重发，设备需补充全新的预密钥。
4. 验证旧凭据无效、已注销目录为空、旧预密钥不可领取后再开放服务。
