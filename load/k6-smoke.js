import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    feed_reads: { executor: 'constant-vus', vus: 20, duration: '2m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const forum = __ENV.FORUM_KEY || 'anu';

export default function () {
  const response = http.get(`${baseUrl}/api/posts?forum=${encodeURIComponent(forum)}&size=20`);
  check(response, { 'feed is 200': (value) => value.status === 200 });
  sleep(0.25);
}
