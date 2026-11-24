# 设备密文信箱

开发机反向代理统一在下列路径前加 `/secretchat`。所有接口和 WebSocket 握手都在 `Authorization: Bearer <device-token>` 请求头传设备凭据。WebSocket 拒绝带查询串的握手。公网使用 HTTPS 或 WSS。账号、设备、消息 ID 和服务端信封引用均采用小写、带连字符的 UUID。

## 发送

客户端先在同一次本地事务中保存加密后的信封、棘轮状态、稳定的 `messageId`、收件设备和绝对到期时间，再向 `POST /api/mailbox/envelopes` 提交 JSON。以下字节仅用于展示 Base64 格式。

```json
{
  "recipientAccountId": "43366ca0-cbe9-4d4a-a232-d6057c43ca34",
  "recipientDeviceId": "4bfd40d0-4204-4fdc-9c3f-cc2e4f53dcf9",
  "messageId": "171c7394-186d-41cf-821a-82574a4b17b8",
  "expiresAtMs": 1800000000000,
  "ciphertext": "AQID"
}
```

`expiresAtMs` 是 Unix 毫秒时间戳，首次接收时必须晚于服务端当前时间且最多相距七天。解码后密文长度为 1–65,535 字节。发送账号和设备来自已认证设备。目录中的联系人关系和目标设备绑定在写入事务内核实。目标设备已更换时返回 `409`，客户端应暂停重试并核验对方新身份。

服务端返回 `202` 和 `{"reference":"<随机 UUID>","expiresAtMs":1800000000000}`。发送设备应保存引用。相同发送设备、目标设备和消息 ID 的重试会取得原回执。重试变更密文或期限返回 `409`。被拉黑期间提交的信封得到相同形式的回执，解除拉黑后重试也不会补发。

## 补拉和确认

1. 启动、回前台、连接恢复或收到 `available` 时，调用 `GET /api/mailbox/envelopes?after=0&limit=50`。`after` 初值也可取本地最后成功提交的游标。`limit` 范围为 1–100。
2. 响应包含 `envelopes`、`through`、`nextAfter` 和 `done`。`through` 固定本轮队尾。`done=false` 时带上相同 `through`，用 `nextAfter` 作为 `after` 继续拉取。期间新增的信封留给下一轮。已确认、到期或撤销的信封可能使页面变短。游标是递增整数，允许跳号。
3. 每个信封包含 `sequence`、`reference`、`messageId`、`senderAccountId`、`senderDeviceId`、`expiresAtMs` 和 Base64 `ciphertext`。客户端本地事务应一起写入处理结果、棘轮状态、游标及待确认引用。处理完 `through` 前暂缓展示这一批消息，以处理跨页撤回。
4. 本地提交后对每个引用调用 `POST /api/mailbox/envelopes/{reference}/ack`。只有原收件设备可以确认。首次和重复确认均返回 `204`。到期后首次确认返回 `410`。个别 ACK 顺序或 ACK 丢失不会确认其他信封。客户端应持久重试未完成的 ACK。

通知扩展可用同一设备凭据调用 `GET /api/mailbox/envelopes/{reference}` 读取单个待投递信封。读取不推进游标或确认收件。确认、到期或其他设备的引用对该读取返回 `404`。

## 状态和唤醒

发送设备通过 `GET /api/mailbox/outcomes/{reference}` 取得 `reference`、`expiresAtMs` 和 `status`。`accepted` 表示服务端已接收，`received` 表示原收件设备已确认，`expired` 表示期限内未确认。已读与撤回完成需要客户端另发加密回执。到期时间一到，读取和确认立即受限，后台任务清理密文。去重和状态记录保留到原到期时间后七天；设备撤销会提前清除与该设备有关的记录。

原生客户端在 `GET /ws` 升级握手中发送设备凭据。服务端发送 JSON 文本，例如 `{"type":"ready"}`。`ready` 表示可以开始补拉并查询本机未结信封状态。`available` 提醒收件设备补拉，`outcome` 提醒发送设备核对未结回执。提示可合并，断线后始终主动补拉和对账。服务端检查设备有效性后发送提示，正常撤销会关闭现有连接，空闲连接每 30 秒复核。

## APNs

设备取得或轮换 APNs 令牌时，调用 `PUT /api/mailbox/push-token` 提交 `{"token":"<十六进制设备令牌>"}`；停用推送时调用 `DELETE /api/mailbox/push-token`。成功返回 `204`。同一设备仅保留当前令牌，重新绑定同一令牌会解除其原设备绑定。

后台任务仅向当前绑定发送固定通知与不透明 `ref`，扩展自行取回密文。任务在派发时复核收件设备和拉黑关系；拉黑后尚未发送的推送会取消。APNs 临时错误最多尝试五次，失效令牌会被解除绑定。推送失败、未配置或设备离线时，客户端仍可主动补拉。APNs 接受一条提醒不会把投递状态改为 `received`。

`schema.sql` 保留已有 `queued_envelope` 和指标查询，`mailbox.sql` 在同一免备份队列库创建回执和推送绑定表。旧版队列表中缺少经验证的发送设备等元数据的行只参与原有指标，按其到期时间清理。WebSocket 唤醒使用单个服务 JVM 内的已提交事件，适用于当前单实例部署。
