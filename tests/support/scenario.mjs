import { randomUUID } from 'node:crypto';

export const expectedSummary = {
  cashA: { CNY: '89000', USD: '15000', JPY: '50000' },
  cashB: { CNY: '42000', USD: '-500', EUR: '7000' },
  spent: { CNY: '12000', USD: '6500', EUR: '3000' },
  debt: { CNY: '5000', USD: '2500', EUR: '-1500' }
};
export function fixture() {
  const t = { id: randomUUID(), secret: Buffer.alloc(32, 7).toString('base64url'), localPerson: 'a',
    name: '虚构跨国骑行', personA: '小明 🚴', personB: '朱桂江' };
  let sequence = 0;
  const entry = (kind, data) => ({ id: randomUUID(), tripId: t.id, kind,
    at: 1700000000000 + (sequence++ % 2 ? -3600000 : 3600000) + sequence, data });
  const events = [
    entry('opening', { person: 'a', currency: 'CNY', amount: 100000 }),
    entry('opening', { person: 'b', currency: 'CNY', amount: 50000 }),
    entry('opening', { person: 'a', currency: 'USD', amount: 20000 }),
    entry('opening', { person: 'b', currency: 'EUR', amount: 10000 }),
    entry('expense', { payer: 'a', currency: 'CNY', amount: 12000, shareA: 5000,
      mode: 'shared', note: '住宿 🏕️ "双床"' }),
    entry('expense', { payer: 'b', currency: 'CNY', amount: 8000, shareA: 2500, mode: 'shared' }),
    entry('expense', { payer: 'b', currency: 'USD', amount: 1500, shareA: 0, mode: 'personal' }),
    entry('topup', { person: 'a', currency: 'JPY', amount: 50000 }),
    entry('exchange', { person: 'b', fromCurrency: 'CNY', fromAmount: 7000,
      toCurrency: 'USD', toAmount: 1000 }),
    entry('transfer', { from: 'b', currency: 'CNY', amount: 1000 }),
    entry('expense', { payer: 'a', currency: 'USD', amount: 5000, shareA: 5000, mode: 'shared' }),
    entry('expense', { payer: 'b', currency: 'EUR', amount: 3000, shareA: 5000, mode: 'shared' })
  ];
  events.push(entry('delete', { target: events[5].id }));
  return { t, events };
}

export function invalidInvites(valid) {
  const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
  return [
    ['truncated code', encode(valid).slice(0, -8)],
    ['short key', encode({ ...valid, secret: 'abc' })],
    ['empty name', encode({ ...valid, name: ' ' })],
    ['same names', encode({ ...valid, personA: 'Alice', personB: ' alice ' })],
    ['numeric name', encode({ ...valid, personA: 123 })],
    ['invalid UUID', encode({ ...valid, id: '-'.repeat(36) })],
    ['empty sharing role', encode({ ...valid, inviterRole: '' })],
    ['null sharing role', encode({ ...valid, inviterRole: null })],
    ['invalid sharing role', encode({ ...valid, inviterRole: 'c' })]
  ].map(([name, code]) => ({ name, code }));
}
