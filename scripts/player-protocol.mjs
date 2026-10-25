import { createCipheriv, createDecipheriv, createHash, pbkdf2Sync, randomBytes } from 'node:crypto';

// Reimplemented from the supplied catalog and the downloaded public player code.
// These constants are client-side protocol values, not account credentials.
export const PLAYER_ORIGIN = 'https://ployan.me';
export const EMBED_ORIGINS = ['https://idgasbhiop.com/e/', 'https://embos.top/'];
export const FALLBACK_ORDER = [2, 4, 1, 3, 5];

function encrypt(text, key, iv) {
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  return Buffer.concat([cipher.update(text, 'utf8'), cipher.final(), cipher.getAuthTag()]);
}
function decrypt(bytes, key, iv) {
  if (bytes.length < 16) throw new Error('Missing AES-GCM authentication tag');
  const cipher = createDecipheriv('aes-256-gcm', key, iv);
  cipher.setAuthTag(bytes.subarray(-16));
  return Buffer.concat([cipher.update(bytes.subarray(0, -16)), cipher.final()]).toString('utf8');
}
function hex(text) {
  if (!/^(?:[0-9a-f]{2})+$/i.test(text)) throw new Error('Invalid hexadecimal field');
  return Buffer.from(text, 'hex');
}
function validateSelection(movie, episode, server) {
  for (const [name, value] of Object.entries({ movie, episode, server })) {
    if (!/^\d+$/.test(String(value))) throw new Error(`${name} must be a numeric ID`);
  }
}

export function sealHandoff({ movie, episode = 1, server = 2, country, timestamp = Math.floor(Date.now()/1000) }, iv = randomBytes(12)) {
  validateSelection(movie, episode, server);
  if (!/^[A-Z]{2}$/.test(country)) throw new Error('Expected two-letter country code');
  const key = createHash('sha256').update(country).digest();
  const text = [movie, episode, server, country, timestamp].join('+');
  const inner = Buffer.concat([iv, encrypt(text, key, iv)]).toString('base64');
  return Buffer.from(inner, 'utf8').toString('base64url');
}
export function openHandoff(fragment, country) {
  const bytes = Buffer.from(Buffer.from(fragment.replace(/^#/, ''), 'base64url').toString('utf8'), 'base64');
  if (bytes.length < 28) throw new Error('Truncated handoff');
  return decrypt(bytes.subarray(12), createHash('sha256').update(country).digest(), bytes.subarray(0,12));
}
export function watchUrl(selection) {
  return `${PLAYER_ORIGIN}/watch/?v${selection.server ?? 2}${selection.episode ?? 1}#${sealHandoff(selection)}`;
}

export function sealResolver(text, { salt = randomBytes(8), iv = randomBytes(12) } = {}) {
  const key = pbkdf2Sync('player', salt, 1000, 32, 'sha256');
  return [salt, iv, encrypt(text, key, iv)].map(b => Buffer.from(b).toString('hex')).join('-');
}
export function openResolver(envelope) {
  const parts = envelope.split('-');
  if (parts.length !== 3) throw new Error('Expected salt-IV-ciphertext envelope');
  const [salt, iv, ciphertext] = parts.map(hex);
  if (salt.length !== 8 || iv.length !== 12) throw new Error('Unexpected salt or IV length');
  return decrypt(ciphertext, pbkdf2Sync('player', salt, 1000, 32, 'sha256'), iv);
}
export function resolverUrl({ movie, episode = 1, server = 2, timestamp = Math.floor(Date.now()/1000) }) {
  validateSelection(movie, episode, server);
  return `${PLAYER_ORIGIN}/get/${sealResolver([movie, episode, server, timestamp].join('+'))}`;
}
export function subtitlePath(movie, episode = 1) {
  const xor = [...'player'].reduce((n, ch) => n ^ ch.charCodeAt(0), 0);
  const id = [...`${movie}-${episode}`].map(ch => (ch.charCodeAt(0) ^ xor).toString(16).padStart(2, '0')).join('');
  return `/sub/${id}/index.json`;
}
export function decodeRoute(response, server) {
  if (response.mode === 'direct') return { mode: 'direct', url: `${PLAYER_ORIGIN}/hls/${response.info}/master.m3u8` };
  if (response.mode !== 'embed') return { mode: response.mode ?? 'unknown' };
  const decoded = openResolver(response.info);
  let url;
  if ([2,4].includes(Number(server))) {
    if (decoded.includes('tv/')) {
      const [show, episode] = decoded.replace('tv/', '').split('-');
      const [movie, season] = show.split('/');
      url = `${EMBED_ORIGINS[1]}tv/?mid=${movie}&s=${season}&e=${episode}`;
    } else url = `${EMBED_ORIGINS[1]}movie/?mid=${decoded.replace('movie/', '').split('-')[0]}`;
  } else {
    const value = decoded.substring(0, decoded.lastIndexOf('-'));
    const parsed = /^https?:\/\//i.test(value) ? new URL(value) : null;
    const filecode = parsed ? parsed.pathname.split('/').filter(Boolean).pop() : value;
    if (!filecode || !/^[A-Za-z0-9_-]+$/.test(filecode)) throw new Error('Invalid provider filecode');
    url = new URL(filecode + (parsed ? parsed.search + parsed.hash : ''), EMBED_ORIGINS[0]).href;
  }
  return { mode: 'embed', decoded, url };
}
