import http from 'k6/http';
import encoding from 'k6/encoding';
import { check, group, sleep, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// A mixed workload, because the smoke test only ever asked one question.
//
// k6-smoke.js reads the feed with twenty virtual users and reports a p95. That
// number is real and it is also the cheapest number this system produces: the
// feed is a cursor-paginated read with no model behind it and no write lock in
// front of it. Nothing in it touches the parts that actually contend — the
// report endpoint's uniqueness constraint, the case queue's SKIP LOCKED claim,
// image normalisation holding a decoded bitmap per request, or the admin console
// listing cases while workers are writing to them.
//
// So this script runs the six things real traffic does, at the ratios real
// traffic does them, and puts a threshold on each separately. One aggregate p95
// over a mix like this is close to meaningless: it would be dominated by the
// cheapest, most frequent call, which is exactly the one that was already known
// to be fine.

// Point it at an environment whose per-account limits have been raised, or it
// measures the limiters rather than the endpoints. Production allows ten
// registrations per address every fifteen minutes, and every virtual user in the
// write, report and upload scenarios registers once; the refused ones try again
// on every iteration and their scenario records almost nothing but 429s.
//
// Raise all five, on the target:
//
//   campusguard.auth-rate-limit.registrations-per-ip
//   campusguard.auth-rate-limit.logins-per-ip
//   campusguard.auth-rate-limit.logins-per-account
//   campusguard.content.posts-per-user
//   campusguard.reports.per-user-limit
//
// logins-per-account is the one that is easy to miss and the only one that can
// fail a budget on its own. It defaults to 12 per fifteen minutes, and a virtual
// user here is one account signing in for the whole run: at 2 logins a second
// across 10 VUs that is 36 attempts per account, so 24 of every 36 come back 429
// and the sign-in failure rate lands near 65%. The first run of this script did
// exactly that — 240 throttled out of ~370 sign-in requests, with every latency
// budget green, because a 429 is fast.
//
// Read campusguard_throttled in the summary to confirm it worked. Any number
// above zero means some scenario measured a limiter, and the run is not a
// baseline.
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
// The production profile serves Actuator on its own port, 9090, so against a
// deployed stack the health check is not under BASE_URL.
const healthUrl = __ENV.HEALTH_URL || `${baseUrl}/actuator/health`;
const forum = __ENV.FORUM_KEY || 'loadtest';
const password = __ENV.LOAD_PASSWORD || 'load-test-correct-horse';

// Admin credentials are optional. Without them the admin scenario is skipped
// rather than failing the run: the console is the one surface a load test
// cannot exercise without a privileged account, and requiring one would mean
// nobody runs this at all.
const adminUsername = __ENV.ADMIN_USERNAME || '';
const adminPassword = __ENV.ADMIN_PASSWORD || '';

const registrations = new Counter('campusguard_registrations');
const postsCreated = new Counter('campusguard_posts_created');
const reportsFiled = new Counter('campusguard_reports_filed');
const throttled = new Counter('campusguard_throttled');
const uploadBytes = new Trend('campusguard_upload_bytes');

export const options = {
  // Ratios taken from what a forum does, not from what is easy to generate:
  // overwhelmingly reading, a steady trickle of writing, and reports and uploads
  // as rare events that nonetheless hit the most expensive paths in the system.
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 30 },
        { duration: '2m', target: 30 },
        { duration: '30s', target: 0 },
      ],
      tags: { workload: 'browse' },
    },
    signIn: {
      executor: 'constant-arrival-rate',
      exec: 'signIn',
      rate: 2,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 10,
      tags: { workload: 'sign-in' },
    },
    write: {
      executor: 'constant-arrival-rate',
      exec: 'write',
      rate: 3,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 20,
      tags: { workload: 'write' },
    },
    report: {
      executor: 'constant-arrival-rate',
      exec: 'report',
      rate: 1,
      timeUnit: '2s',
      duration: '3m',
      preAllocatedVUs: 10,
      tags: { workload: 'report' },
    },
    upload: {
      executor: 'constant-arrival-rate',
      exec: 'upload',
      rate: 1,
      timeUnit: '3s',
      duration: '3m',
      preAllocatedVUs: 10,
      tags: { workload: 'upload' },
    },
    admin: {
      executor: 'constant-arrival-rate',
      exec: 'admin',
      rate: 1,
      timeUnit: '2s',
      duration: '3m',
      preAllocatedVUs: 5,
      tags: { workload: 'admin' },
    },
  },

  // Per-workload budgets. A single global p95 would be set by `browse`, which
  // is 80% of the requests and the fastest of them, and would stay green while
  // the report endpoint doubled in latency.
  thresholds: {
    'http_req_failed{workload:browse}': ['rate<0.01'],
    'http_req_failed{workload:sign-in}': ['rate<0.01'],
    'http_req_failed{workload:admin}': ['rate<0.01'],
    'http_req_duration{workload:browse}': ['p(95)<500'],
    // Writing is a transaction and an insert; slower than a read and still
    // interactive.
    'http_req_duration{workload:write}': ['p(95)<800'],
    // A report contends on the per-target uniqueness constraint that collapses
    // duplicate reports into one case, and it is the write most likely to be
    // filed by many people at once about one post.
    'http_req_duration{workload:report}': ['p(95)<1000'],
    // Normalisation decodes and re-encodes the image in the request thread, so
    // this budget is about heap and CPU rather than about the database.
    'http_req_duration{workload:upload}': ['p(95)<2500'],
    // The case list runs while workers hold FOR UPDATE SKIP LOCKED rows. It is
    // the query that a reviewer waits on during exactly the incident that
    // generates the load.
    'http_req_duration{workload:admin}': ['p(95)<1200'],
  },
};

// One 8x8 PNG, inline, so a run needs no fixture file and every upload posts
// identical bytes. Image *content* is not what is being measured here; the
// decode-normalise-encode path is, and it costs the same either way.
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAIAQMAAAD+wSzIAAAABlBMVEX///+/v7+jQ3Y5AAAADklEQVQI12P4AIX8EAgALgAD/aNpbtEAAAAASUVORK5CYII=';

function json(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

/**
 * A throttled response is a pass, not a failure.
 *
 * The rate limiters are a feature: they exist to bound what one account can do
 * to a thread and what one page can cost. A load generator pretending to be one
 * user at 3 writes a second is precisely the traffic they are built to refuse,
 * so counting 429s as errors would make a working limiter look like an outage
 * and make the thresholds untunable.
 */
function ok(response, name, allowed) {
  if (response.status === 429) {
    throttled.add(1, { endpoint: name });
    return false;
  }
  check(response, { [name]: (value) => allowed.includes(value.status) });
  return allowed.includes(response.status);
}

function register() {
  const username = `load_${__VU}_${__ITER}_${randomIntBetween(1, 1e9)}`;
  const response = http.post(
    `${baseUrl}/api/auth/register`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'register' } },
  );
  if (!ok(response, 'register accepted', [200, 201])) {
    return null;
  }
  registrations.add(1);
  const body = json(response);
  return body && body.accessToken ? { token: body.accessToken, username } : null;
}

function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

/** Cached per VU: a virtual user is one person, and one person signs in once. */
let session = null;
let adminSession = null;

function member() {
  if (session === null) {
    session = register();
  }
  return session;
}

export function browse() {
  group('read the feed and one thread', () => {
    const feed = http.get(
      `${baseUrl}/api/posts?forum=${encodeURIComponent(forum)}&size=20`,
      { tags: { endpoint: 'feed' } },
    );
    if (!ok(feed, 'feed is 200', [200])) {
      return;
    }

    const body = json(feed);
    const posts = body && body.items ? body.items : [];
    if (posts.length === 0) {
      return;
    }

    // Reading a thread, not just the list. Comments are the read that fans out.
    const post = posts[randomIntBetween(0, posts.length - 1)];
    const comments = http.get(
      `${baseUrl}/api/posts/${post.id}/comments?size=20`,
      { tags: { endpoint: 'comments' } },
    );
    ok(comments, 'comments are 200', [200]);
  });
  sleep(randomIntBetween(1, 3));
}

export function signIn() {
  const identity = member();
  if (identity === null) {
    return;
  }
  const response = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ username: identity.username, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login' } },
  );
  // Password hashing is deliberately expensive, so logins are the one cheap-looking
  // endpoint that is CPU-bound by design.
  ok(response, 'login accepted', [200]);
}

export function write() {
  const identity = member();
  if (identity === null) {
    return;
  }

  const created = http.post(
    `${baseUrl}/api/posts`,
    JSON.stringify({
      forumKey: forum,
      title: `Load post ${__VU}-${__ITER}`,
      body: 'Generated by k6-mixed.js. Ordinary campus content, nothing for a moderator.',
    }),
    { ...authHeaders(identity.token), tags: { endpoint: 'create-post' } },
  );
  if (!ok(created, 'post created', [201])) {
    return;
  }
  postsCreated.add(1);

  const post = json(created);
  if (post && post.id) {
    const comment = http.post(
      `${baseUrl}/api/posts/${post.id}/comments`,
      JSON.stringify({ parentCommentId: null, body: 'Commenting under load.' }),
      { ...authHeaders(identity.token), tags: { endpoint: 'create-comment' } },
    );
    ok(comment, 'comment created', [201]);
  }
}

/**
 * Reports are the interesting write. Several users reporting one post is the
 * path that contends on the per-target uniqueness constraint and then enqueues
 * a case, so this scenario deliberately aims many reporters at a small set of
 * targets rather than spreading them out.
 */
export function report() {
  const identity = member();
  if (identity === null) {
    return;
  }

  const feed = http.get(
    `${baseUrl}/api/posts?forum=${encodeURIComponent(forum)}&size=5`,
    { tags: { endpoint: 'feed-for-report' } },
  );
  if (!ok(feed, 'feed is 200', [200])) {
    return;
  }
  const body = json(feed);
  const posts = body && body.items ? body.items : [];
  if (posts.length === 0) {
    return;
  }

  const target = posts[randomIntBetween(0, posts.length - 1)];
  const response = http.post(
    `${baseUrl}/api/reports`,
    JSON.stringify({ targetType: 'POST', targetId: target.id, reason: 'SPAM', details: 'k6-mixed' }),
    { ...authHeaders(identity.token), tags: { endpoint: 'report' } },
  );
  // 409 is the same account reporting the same target twice, which is the
  // constraint doing its job rather than a fault.
  if (ok(response, 'report accepted or already filed', [201, 409])) {
    reportsFiled.add(1);
  }
}

export function upload() {
  const identity = member();
  if (identity === null) {
    return;
  }

  const bytes = encoding.b64decode(PNG_BASE64);
  uploadBytes.add(bytes.byteLength);

  const response = http.post(
    `${baseUrl}/api/media`,
    { file: http.file(bytes, 'load.png', 'image/png') },
    { headers: { Authorization: `Bearer ${identity.token}` }, tags: { endpoint: 'upload' } },
  );
  ok(response, 'upload accepted', [200, 201]);
}

/**
 * The reviewer's view, under the load that produced the queue.
 *
 * Read-only on purpose: deciding cases would consume the queue this run is
 * building and make two consecutive runs incomparable. What is measured is the
 * case list, which is the query a reviewer waits on while workers are writing
 * to the same rows.
 */
export function admin() {
  if (!adminUsername || !adminPassword) {
    return;
  }

  if (adminSession === null) {
    const login = http.post(
      `${baseUrl}/api/auth/login`,
      JSON.stringify({ username: adminUsername, password: adminPassword }),
      { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'admin-login' } },
    );
    if (login.status !== 200) {
      fail(`admin login failed with ${login.status}; unset ADMIN_USERNAME to skip this scenario`);
    }
    const body = json(login);
    adminSession = body ? body.accessToken : null;
  }
  if (adminSession === null) {
    return;
  }

  const cases = http.get(
    `${baseUrl}/api/admin/moderation-cases?status=AWAITING_REVIEW&size=20`,
    { ...authHeaders(adminSession), tags: { endpoint: 'case-list' } },
  );
  ok(cases, 'case list is 200', [200]);
}

export function setup() {
  const health = http.get(healthUrl);
  if (health.status !== 200) {
    fail(`${healthUrl} answered ${health.status}; start the backend first, or set HEALTH_URL`);
  }
  return {};
}

export function handleSummary(data) {
  // Written next to the script so two runs can be diffed. A load test whose
  // output only ever went to a terminal cannot answer "is this release slower
  // than the last one", which is the only question worth running it for.
  return {
    stdout: describe(data),
    'load/k6-mixed-summary.json': JSON.stringify(data, null, 2),
  };
}

/**
 * Everything worth reading from a run, printed here because handleSummary
 * replaces k6's own end-of-test summary rather than adding to it: once it
 * returns stdout, the built-in thresholds table is never shown. So every budget
 * is listed with the figure it was held to and whether it held, followed by the
 * throttle count that says whether the run measured the endpoints or the
 * limiters.
 */
function describe(data) {
  const lines = ['', 'Budgets:'];

  Object.keys(data.metrics).sort().forEach((name) => {
    const metric = data.metrics[name];
    Object.keys(metric.thresholds || {}).forEach((rule) => {
      const stat = rule.split(/[<>=]/)[0];
      const value = metric.values ? metric.values[stat] : undefined;
      const shown = value === undefined
        ? 'no samples'
        : metric.type === 'rate' ? `${(value * 100).toFixed(2)}%` : `${value.toFixed(1)} ms`;
      lines.push(`  ${metric.thresholds[rule].ok ? 'ok    ' : 'FAILED'}  ${name}  ${stat} ${shown}  (${rule})`);
    });
  });

  const requests = data.metrics.http_reqs;
  const duration = data.metrics.http_req_duration;
  const throttledCount = data.metrics.campusguard_throttled ? data.metrics.campusguard_throttled.values.count : 0;
  lines.push('');
  if (requests && duration) {
    lines.push(`  ${requests.values.count} requests, overall p(95) ${duration.values['p(95)'].toFixed(1)} ms`);
  }
  lines.push(`  campusguard_throttled ${throttledCount}`);
  lines.push('');
  return lines.join('\n');
}
