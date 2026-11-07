// Benchmarks GET /auctions at a fixed request rate. Run it with a live dashboard at http://localhost:5665 and an HTML report at the end:
//   K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_OPEN=true K6_WEB_DASHBOARD_EXPORT=benchmark/report.html DOMAIN=localhost:8081 RATE=500 k6 run benchmark/list-auctions.js
//
// Environment:
//   DOMAIN    required, with or without a scheme, such as localhost:8080 or https://auctions.example.com
//   RATE      requests per second at full load, default 100
//   DURATION  time at full load, default 1m
//   VUS       virtual users allocated up front, default 50
//   MAX_VUS   most virtual users k6 may add to keep up with RATE, default 500
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

const rate = Number(__ENV.RATE || 100);
const preAllocatedVUs = Number(__ENV.VUS || 50);
const maxVUs = Math.max(preAllocatedVUs, Number(__ENV.MAX_VUS || 500));

export const options = {
  scenarios: {
    list_auctions: {
      // Starts iterations at a set rate, independent of how fast responses return,
      // so slow responses show up as higher latency instead of fewer requests.
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs,
      maxVUs,
      stages: [
        { duration: '10s', target: rate },
        { duration: __ENV.DURATION || '1m', target: rate },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const response = http.get(url, params);
  check(response, {
    'status is 200': (r) => r.status === 200,
    'has items': (r) => r.status === 200 && Array.isArray(r.json('items')),
  });
}
