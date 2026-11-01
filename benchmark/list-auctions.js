// Benchmarks GET /auctions. Run it with a live dashboard at http://localhost:5665 and an HTML report at the end:
//   K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_OPEN=true K6_WEB_DASHBOARD_EXPORT=benchmark/report.html \
//     DOMAIN=localhost:8080 k6 run benchmark/list-auctions.js
//
// Environment:
//   DOMAIN    required, with or without a scheme, such as localhost:8080 or https://auctions.example.com
//   VUS       virtual users at full load, default 50
//   DURATION  time at full load, default 1m
//   PAGE      page to request, default 0
//   SIZE      auctions per page, default 20
import http from 'k6/http';
import { check } from 'k6';

const domain = __ENV.DOMAIN;
if (!domain) {
  throw new Error('Set DOMAIN, for example: DOMAIN=localhost:8080 k6 run benchmark/list-auctions.js');
}

const baseUrl = (/^https?:\/\//.test(domain) ? domain : `http://${domain}`).replace(/\/+$/, '');
const page = __ENV.PAGE || '0';
const size = __ENV.SIZE || '20';
const url = `${baseUrl}/auctions?page=${page}&size=${size}`;
const params = { headers: { accept: '*/*' }, tags: { name: 'GET /auctions' } };

export const options = {
  stages: [
    { duration: '10s', target: Number(__ENV.VUS || 50) },
    { duration: __ENV.DURATION || '1m', target: Number(__ENV.VUS || 50) },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

// No sleep, so each virtual user sends its next request as soon as the last one returns. This measures throughput.
export default function () {
  const response = http.get(url, params);
  check(response, {
    'status is 200': (r) => r.status === 200,
    'has items': (r) => r.status === 200 && Array.isArray(r.json('items')),
  });
}
