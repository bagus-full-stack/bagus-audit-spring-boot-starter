// k6 script: 30 writers and 20 readers (each reader walks 5 pages of 50 with the cursor) for 40 seconds.
//   see docker/load-test.sh
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE;
const json = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    writers: { executor: 'constant-vus', vus: 30, duration: '40s', exec: 'write' },
    readers: { executor: 'constant-vus', vus: 20, duration: '40s', exec: 'read' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:writers}': ['p(95)<500'],
    'http_req_duration{scenario:readers}': ['p(95)<500'],
  },
};

export function write() {
  const r = http.post(`${BASE}/api/audit`, JSON.stringify({ action: 'LOAD', payload: 'x'.repeat(200) }),
    { headers: { ...json, Authorization: `Bearer ${__ENV.WRITER}` } });
  check(r, { created: (x) => x.status === 201 });
}

export function read() {
  let cursor = '';
  for (let i = 0; i < 5; i++) {
    const r = http.get(`${BASE}/api/audit?size=50${cursor ? `&cursor=${cursor}` : ''}`,
      { headers: { Authorization: `Bearer ${__ENV.READER}` } });
    check(r, { ok: (x) => x.status === 200 });
    cursor = r.json('nextCursor');
    if (!cursor) break;
  }
}
