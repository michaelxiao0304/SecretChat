# 账号与设备 API

以下路径相对于服务根目录。共用域名部署时加上 `/secretchat`。请求和响应使用 JSON。私有接口需要 `Authorization: Bearer <token>`。客户端在安全存储中保存设备令牌，邀请码与设备令牌均仅在签发时返回原值。

## 首个账号与邀请

控制台管理员可通过 `GET /admin-api/invites/settings` 查询 `{limit,bootstrapAvailable}`。在目录为空时，管理员用 `POST /admin-api/invites` 签发首个账号的邀请，返回 `{id,code}`。再次签发会撤销上一个首账号邀请码。首个账号注册后此入口返回 409。最后一个账号注销后，管理员可重新签发。管理员可用 `PUT /admin-api/invites/settings` 发送 `{"limit":5}`，设置每个账号可同时持有的未兑换邀请数，范围为 0 至 100，默认 5。调低上限后，已签发邀请仍可使用。管理员写请求携带当前会话的 CSRF 头。

| 设备接口 | 行为 |
|---|---|
| `POST /api/invites` | 生成单次凭据，返回 `{id,code}`。达到上限返回 409。 |
| `GET /api/invites` | 返回自己签发的邀请数组，每项含 `id` 及 `redeemedBy`。未兑换时后者为 null。 |
| `DELETE /api/invites/{id}` | 撤销自己尚未兑换的邀请，返回 204；可安全重试。 |
| `POST /api/invites/redeem` | 已登录设备发送 `{"code":"…"}`，成功返回 204，双方成为联系人。原兑换方重试返回 204，其他账号再使用返回 409。 |

## 注册与找回

当前按 IP 联调时，先在控制台的“邀请管理”中生成首个邀请码，再向 `POST /secretchat/api/accounts` 发送以下 JSON。测试标识可以自行填写。示例中的公钥字节只适合验证接口流程。

```json
{
  "email": "demo-alice",
  "inviteCode": "<控制台刚生成的一次性邀请码>",
  "identityKey": "AQ==",
  "signedPrekey": "Ag=="
}
```

响应状态为 201，返回 `accountId`、`deviceId` 和只显示一次的设备 `token`。使用该令牌调用 `POST /secretchat/api/invites` 给第二个测试账号发邀请。

默认开启邮箱验证码。向 `POST /api/challenges` 发送 `{"email":"name@example.test","purpose":"register"}` 或 `purpose:"recover"`，通过邮件收到六位数验证码，接口返回 `{"challengeId":"…"}`。同一邮箱仅最新一次请求有效，10 分钟到期，最多校验五次。每小时每个邮箱最多发送 3 次，每个来源地址最多发送 20 次，超限返回 429。邮件不可用时返回 503。生产环境需提供 `SECRETCHAT_CHALLENGE_SECRET`，使用至少 32 字节的随机密钥，所有服务实例共享。多副本间使用相同密钥才能验证同一个验证码。轮换密钥将使未使用的验证码失效。

客户端在设备生成密钥。注册时向 `POST /api/accounts` 提交以下请求；找回时用 `POST /api/accounts/recover`，填写用途为 recover 的凭据，并省略 `inviteCode`。

```json
{
  "challengeId": "<邮箱验证请求 ID>",
  "code": "123456",
  "inviteCode": "<邀请凭据>",
  "identityKey": "AQ==",
  "signedPrekey": "Ag==",
  "protocolPrekeys": "Aw=="
}
```

示例中的 Base64 值仅展示字段编码。各密钥字段由客户端按约定的 libsignal 版本序列化，`signedPrekey` 包含签名、ID 及公钥，`protocolPrekeys` 可包含协议所需的额外公开材料。每个字段解码后限 16 KiB。`protocolPrekeys` 可省略。设备校验签名并在本地保存经核验的联系人身份。

注册成功返回 201 及 `{accountId,deviceId,token}`。同一邮箱只能创建一个账号。找回成功返回 200 和一套新的设备 ID、登录令牌，账号 ID 稳定。找回请求必须携带新的身份公钥；重复提交旧身份公钥返回 409。旧设备凭据随事务提交失效，旧设备预密钥、推送绑定以及关联的待投递数据一并清理。错误、到期或已用验证码返回 401。

开发联调设置 `SECRETCHAT_REGISTRATION_UNVERIFIED=true` 时，注册请求直接提交 `email`、`inviteCode` 以及设备公钥材料，省略 `challengeId` 和 `code`。此模式允许任意非空、无控制字符、最多 254 字符的标识，按去除首尾空白、小写化后的值保证唯一，注册全程无需邮件。重复注册返回 409，原设备继续有效。找回仍需向该邮箱实际发送并验证验证码，使用非邮箱标识的测试账号无法通过邮件找回。

## 联系人、拉黑与密钥补给

| 设备接口 | 请求与结果 |
|---|---|
| `GET /api/contacts` | 返回双方已建立关系的联系人，每项含 `accountId,deviceId,identityKey,blocked`。客户端对照本地绑定，发现已核验身份变更时暂停发送并提示重新核验。 |
| `PUT /api/contacts/{accountId}/block`、`DELETE /api/contacts/{accountId}/block` | 拉黑、解除拉黑，返回 204。 |
| `POST /api/keys/one-time` | 提交 `{"keys":[{"id":7,"material":"BA=="}]}`，返回 `{"remaining":1}`。每次 1–100 项，ID 为 0–4294967295，设备为每个新密钥分配新的 ID。同一设备重试相同的上传可安全完成。 |
| `GET /api/keys/one-time` | 返回当前设备可领取的一次性预密钥余量。 |
| `PUT /api/keys/signed` | 提交 `{"signedPrekey":"…","protocolPrekeys":"…"}` 更新签名预密钥及可选的额外公开材料，成功返回 204。 |
| `POST /api/contacts/{accountId}/prekeys` | 返回 `{accountId,deviceId,identityKey,signedPrekey,protocolPrekeys,oneTimePrekey}`。若领取到一次性预密钥，字段值为 `{id,material}`；发完后为 null，客户端仍可取得签名预密钥。服务端原子领取并移除密钥材料，记录已用 ID，以防旧上传重试使密钥再次可领取。 |
| `DELETE /api/accounts/me` | 客户端确认注销后调用，成功返回 204。同一事务删除账号及设备、邀请关系、联系人、拉黑、绑定及待投递数据，向独立撤销日志写入注销。 |

目录数据位于 `sc_directory`，撤销日志位于 `sc_ledger.revocations`。换机写入 `DEVICE_RETIRED`，注销写入 `ACCOUNT_CLOSED`。恢复前停服，使用完整、可信的撤销日志逐项清除被撤销设备、已注销账号并使未领取的一次性密钥失效；作废恢复出的邮箱挑战，再开放服务。恢复后客户端上传全新的一次性密钥及 ID。备份时排除队列和撤销日志，独立保管后者并覆盖最长备份周期。
