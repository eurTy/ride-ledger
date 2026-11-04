import { readFile } from 'node:fs/promises';

const metadata = JSON.parse(await readFile(new URL('./metadata/testflight.zh-Hans.json', import.meta.url), 'utf8'));
let contacts = {};
try { contacts = JSON.parse(process.env.ASC_CONTACT_JSON || '{}'); }
catch { throw new Error('ASC_CONTACT_JSON must be valid JSON. Its values were not printed.'); }
const missing = [];
const email = value => typeof value === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
if (!email(contacts.feedbackEmail)) missing.push('ASC_CONTACT_JSON.feedbackEmail');
for (const field of ['firstName', 'lastName', 'phone'])
  if (typeof contacts.reviewContact?.[field] !== 'string' || !contacts.reviewContact[field].trim()) missing.push('ASC_CONTACT_JSON.reviewContact.' + field);
if (!email(contacts.reviewContact?.email)) missing.push('ASC_CONTACT_JSON.reviewContact.email');
try { if (new URL(metadata.privacyPolicyURL).protocol !== 'https:') throw new Error(); }
catch { missing.push('privacyPolicyURL'); }
if (metadata.bundleId !== 'com.eurty.rideledger') missing.push('bundleId must match the Xcode project');
if (missing.length) {
  const message = 'TestFlight external-review information still needed: ' + missing.join(', ');
  if (process.argv.includes('--draft')) { console.log(message); process.exit(0); }
  throw new Error(message);
}
console.log('TestFlight metadata is ready to enter in App Store Connect. Export compliance remains an account-holder action after Apple processes the build.');
