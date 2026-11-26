const decode64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));

export async function decryptReport(report, pem) {
  if (!globalThis.crypto?.subtle) throw new Error('请通过受信任的 HTTPS 连接打开控制台。');
  if (report.version !== 1) throw new Error('未知举报版本。');
  const match = /^\s*-----BEGIN PRIVATE KEY-----\s+([A-Za-z0-9+/=\s]+)-----END PRIVATE KEY-----\s*$/.exec(pem);
  if (!match) throw new Error('请选择 PKCS#8 PEM 私钥文件。');
  const rsa = await crypto.subtle.importKey('pkcs8', decode64(match[1]), {name:'RSA-OAEP',hash:'SHA-256'}, false, ['unwrapKey']);
  const aes = await crypto.subtle.unwrapKey('raw', decode64(report.wrappedKey), rsa, {name:'RSA-OAEP'},
    {name:'AES-GCM'}, false, ['decrypt']);
  if (aes.algorithm.length !== 256) throw new Error('无效的举报密钥。');
  const context = ['SecretChat/report/v1',report.id,report.reporterAccountId,report.reportedAccountId,report.keyId].join('\n');
  const clear = await crypto.subtle.decrypt({name:'AES-GCM',iv:decode64(report.iv),tagLength:128,
    additionalData:new TextEncoder().encode(context)}, aes, decode64(report.ciphertext));
  return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(clear));
}
