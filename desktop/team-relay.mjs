import { createHmac } from 'node:crypto';
import { openBundle, peerAad, sealPeer } from './peer-protocol.mjs';
import { Admission, verifySignature } from './team.mjs';

const domain = 'ride-ledger-team-enrollment-1';
const ownerDomain = 'ride-ledger-team-relay-owner-1';

export async function enrollViaRelay(trip, directory, isCurrent, request = fetch) {
  const mac = message => createHmac('sha256', trip.key).update(message).digest('base64url');
  const token = mac(domain + ':room:' + trip.fingerprint);
  const message = (id, kind) => ({ domain, team: trip.fingerprint, id, kind });
  const sealed = body => sealPeer(trip, { ...body, signature: directory.sign(body) });
  const unpack = (bundle, id, kind, key) => {
    const { signature, ...body } = openBundle(trip, bundle, peerAad(trip));
    if (body.domain !== domain || body.team !== trip.fingerprint || body.id !== id || body.kind !== kind ||
        !verifySignature(key ?? body.key, body, signature)) throw new Error('设备登记消息无效');
    return body;
  };
  const send = async body => {
    const response = await request(trip.syncUrl + '/team-enrollment', { method: 'POST', redirect: 'error',
      signal: AbortSignal.timeout(6500), headers: { 'Content-Type': 'application/json', 'X-Trip-Token': token },
      body: JSON.stringify({ issuerKey: trip.team.issuerKey, ...body }) });
    if (!response.ok) throw new Error('HTTPS 登记服务暂不可用');
    const parts = [];
    let bytes = 0;
    for await (const part of response.body) {
      bytes += part.length;
      if (bytes > 48 * 1024) throw new Error('登记消息过大');
      parts.push(part);
    }
    return JSON.parse(Buffer.concat(parts).toString('utf8'));
  };
  if (trip.team.issuer === directory.device) {
    const proof = directory.sign({ domain: ownerDomain, token });
    const poll = answers => send({ action: 'issuer', proof, answers });
    const { pending } = await poll([]);
    const answers = [];
    if (!isCurrent(trip)) return '';
    for (const { id, request: bundle } of pending) {
      const decision = message(id, 'decision');
      try { decision.grant = directory.enroll(trip, unpack(bundle, id, 'request')); }
      catch (error) {
        if (!(error instanceof Admission)) continue;
        decision.reason = error.message;
      }
      answers.push({ id, decision: sealed(decision) });
    }
    if (answers.length) await poll(answers);
    return 'HTTPS 登记已就绪';
  }

  if (directory.ready(trip)) return '';
  const peer = { device: directory.device, key: directory.key, member: trip.localPerson };
  const id = mac(`${domain}:request:${peer.device}:${peer.key}:${peer.member}`);
  const { decision } = await send({ action: 'submit', id, request: sealed({ ...message(id, 'request'), ...peer }) });
  if (!isCurrent(trip) || directory.ready(trip)) return '';
  if (!decision) return '等待建队设备联网并打开这趟旅行';
  const result = unpack(decision, id, 'decision', trip.team.issuerKey);
  if (result.reason) throw new Admission(result.reason);
  directory.accept(trip, result.grant, peer);
  return '首次登记完成';
}
