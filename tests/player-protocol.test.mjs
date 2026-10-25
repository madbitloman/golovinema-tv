import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { sealHandoff, openHandoff, sealResolver, openResolver, decodeRoute, subtitlePath } from '../scripts/player-protocol.mjs';

// Independent WebCrypto implementations of the browser algorithms, compared to
// the Node/OpenSSL implementation used by the inspector. Fixed IVs are test-only.
const utf8 = new TextEncoder();
const iv = Uint8Array.from({length:12}, (_,i)=>i);
const salt = Uint8Array.from({length:8}, (_,i)=>16+i);

test('catalog handoff matches browser WebCrypto bytes, including the double Base64 layer', async () => {
  const selection={movie:2107,episode:1,server:2,country:'CA',timestamp:1791987000};
  const digest=await webcrypto.subtle.digest('SHA-256',utf8.encode('CA'));
  const key=await webcrypto.subtle.importKey('raw',digest,{name:'AES-GCM'},false,['encrypt']);
  const encrypted=await webcrypto.subtle.encrypt({name:'AES-GCM',iv},key,utf8.encode('2107+1+2+CA+1791987000'));
  const expected=Buffer.from(Buffer.concat([iv,Buffer.from(encrypted)]).toString('base64')).toString('base64url');
  assert.equal(sealHandoff(selection,iv),expected);
  assert.equal(openHandoff(expected,'CA'),'2107+1+2+CA+1791987000');
  assert.throws(()=>openHandoff(expected,'US'));
});

test('resolver envelope matches the browser PBKDF2/AES-GCM algorithm', async () => {
  const text='2101+1+1+1791987000';
  const seed=await webcrypto.subtle.importKey('raw',utf8.encode('player'),'PBKDF2',false,['deriveKey']);
  const key=await webcrypto.subtle.deriveKey({name:'PBKDF2',salt,iterations:1000,hash:'SHA-256'},seed,{name:'AES-GCM',length:256},false,['encrypt']);
  const encrypted=await webcrypto.subtle.encrypt({name:'AES-GCM',iv},key,utf8.encode(text));
  const expected=[salt,iv,Buffer.from(encrypted)].map(b=>Buffer.from(b).toString('hex')).join('-');
  assert.equal(sealResolver(text,{salt,iv}),expected);
  assert.equal(openResolver(expected),text);
  const tampered=expected.slice(0,-2)+(expected.endsWith('00')?'01':'00');
  assert.throws(()=>openResolver(tampered));
  assert.throws(()=>openResolver('not-an-envelope'));
});

test('decrypts the real captured Hope lookup to its verified embed URL', () => {
  const response={mode:'embed',info:'2d26870b2080d760-b8e23979baa1fef57b1e1f3e-d808a983f41cd9163dc4b6eec97d6344e28e70cfbad865a28e34ebce83e4fd80ecd65072dc9b362c'};
  assert.deepEqual(decodeRoute(response,2),{
    mode:'embed',decoded:'movie/1058424-1791987477',url:'https://embos.top/movie/?mid=1058424'
  });
});

test('routes series and alternate filecodes without confusing the two ID formats', () => {
  const tv={mode:'embed',info:sealResolver('tv/123/2-3')};
  assert.equal(decodeRoute(tv,4).url,'https://embos.top/tv/?mid=123&s=2&e=3');
  const file={mode:'embed',info:sealResolver('https://example.test/e/abc_def?foo=1-1791987000')};
  assert.equal(decodeRoute(file,5).url,'https://idgasbhiop.com/e/abc_def?foo=1');
  assert.equal(subtitlePath(2107,1),'/sub/212223243e22/index.json');
});
