import { generateKeyPairSync, createHash, randomBytes, publicEncrypt, createCipheriv } from 'node:crypto';

export function encryptedFixture(payload, bits = 256) {
  const keys = generateKeyPairSync('rsa', {modulusLength:2048});
  const spki = keys.publicKey.export({type:'spki', format:'der'});
  const keyId = createHash('sha256').update(spki).digest('hex');
  const id = crypto.randomUUID(), reporterAccountId = crypto.randomUUID(), reportedAccountId = crypto.randomUUID();
  const secret = randomBytes(bits / 8), iv = randomBytes(12);
  const cipher = createCipheriv('aes-' + bits + '-gcm', secret, iv);
  cipher.setAAD(Buffer.from(['SecretChat/report/v1',id,reporterAccountId,reportedAccountId,keyId].join('\n')));
  const evidence = {messages:[{text:'自愿提交的测试消息'}]};
  const ciphertext = Buffer.concat([cipher.update(payload ?? JSON.stringify(evidence)), cipher.final(), cipher.getAuthTag()]);
  const wrappedKey = publicEncrypt({key:keys.publicKey, oaepHash:'sha256'}, secret);
  return {publicKey:spki.toString('base64'), pem:keys.privateKey.export({type:'pkcs8',format:'pem'}), evidence,
    report:{id,reporterAccountId,reportedAccountId,keyId,version:1,wrappedKey:wrappedKey.toString('base64'),
      ciphertext:ciphertext.toString('base64'),iv:iv.toString('base64')}};
}
