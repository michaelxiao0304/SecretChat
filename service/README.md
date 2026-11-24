# 服务端与运营控制台

这一版提供管理员登录和信封队列概览。从仓库根目录构建时，Maven 将 `console/src/` 一并打入服务端 JAR，页面和 API 使用同源会话。队列接口返回未过期信封的数量及最老一封的等待秒数。空队列返回 `{"pendingCount":0,"oldestWaitSeconds":null}`，过期边界按服务端时钟计算。

## 本地启动

需要 JDK 17。在仓库根目录运行以下命令。输入的密码仅供本次启动使用；可通过 `SECRETCHAT_ADMIN_USERNAME` 修改默认用户名 `admin`。

```sh
cd service
./mvnw verify
read -rs SECRETCHAT_ADMIN_PASSWORD
export SECRETCHAT_ADMIN_PASSWORD
java -jar target/service.jar --spring.profiles.active=local
```

打开 [本地运营控制台](http://127.0.0.1:8080/console/)。`local` 配置使用内存 H2，启动时建表，默认只监听回环地址。重新启动会清空本地数据。刷新按钮重新读取数据库，页面会显示错误并允许重试。退出登录会使服务端会话失效。

## MySQL 与反向代理

为队列准备独立的 MySQL 数据库，使用有建表权限的账号在该库执行 [schema.sql](src/main/resources/schema.sql)。本版运行账号仅需读取此表。以普通配置启动同一 JAR，并通过环境变量传入连接信息。

| 变量 | 用途 |
|---|---|
| `SECRETCHAT_DB_URL` | 队列专用库的 JDBC URL，例如 `jdbc:mysql://127.0.0.1:3306/secretchat_queue` |
| `SECRETCHAT_DB_USERNAME`、`SECRETCHAT_DB_PASSWORD` | 数据库账号凭据 |
| `SECRETCHAT_ADMIN_USERNAME`、`SECRETCHAT_ADMIN_PASSWORD` | 独立的控制台管理员账号；密码在启动时必须提供 |
| `SECRETCHAT_COOKIE_PATH` | 挂载到既有站点时设为 `/secretchat/`；本地根路径默认为 `/` |
| `PORT` | 本机监听端口，默认 `8080` |

在 Nginx 上将项目的 `/secretchat/console/…` 和 `/secretchat/admin-api/…` 去掉 `/secretchat` 前缀转发到 Spring Boot。控制台入口和主页由 Spring Security 校验；登录页及其必要静态资源允许匿名加载。正式配置使用 Secure、HttpOnly、SameSite=Strict 的独立会话 Cookie，经 HTTPS 访问。现有 demo 的 `/login` 使用自己的路由和账号。

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

服务端测试包含指标计算单测、真实 HTTP 登录、权限和 CSRF 检查、队列聚合、页面访问与会话失效。默认数据库是隔离的 H2。要复跑同一套 MySQL 测试，准备名为 `secretchat_test` 的空测试库，设置 `SECRETCHAT_TEST_DB_URL`、`SECRETCHAT_TEST_DB_USERNAME`、`SECRETCHAT_TEST_DB_PASSWORD` 后运行 `./mvnw test`。测试账号需要在该库建表和读写测试数据。

控制台单测验证 API 请求、CSRF 表单、等待时间格式、页面加载、重试与错误状态。Chrome 测试通过 `/secretchat/` 代理前缀连接真实服务，验证错误密码、登录后读数、刷新、退出和会话过期。
