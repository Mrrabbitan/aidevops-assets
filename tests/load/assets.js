import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

// Never print this input: it contains bearer credentials. No token reaches summary output.
const tokenPath = __ENV.TOKENS_FILE || '/tmp/aidevops-assets-load.tokens.json';
function readPrivateInput() { try { return JSON.parse(open(tokenPath)); } catch (_) { throw new Error('Cannot parse the private input file; contents suppressed.'); } }
const input = readPrivateInput();
if (!Array.isArray(input.accessTokens)) throw new Error('accessTokens must be an array; file contents suppressed.');
const people = new SharedArray('distinct authorized load identities', () => input.accessTokens || []);
const mode = __ENV.MODE || 'qps';
const users = Number(__ENV.USERS || 200);
const rate = Number(__ENV.RATE || 40);
const durationSeconds = Number(__ENV.DURATION_SECONDS || 60);
const readPercent = Number(__ENV.READ_PERCENT || 80);
const onlinePeriod = Number(__ENV.ONLINE_PERIOD_SECONDS || users / rate);
const apiBase = __ENV.ASSETS_API || input.apiBase;
const issuer = 'http://localhost:19383/realms/assets';
const seedAssetIds = input.seedAssetIds || [];

function invalid(message) { throw new Error(message); }
if (!['qps', 'online'].includes(mode)) invalid('MODE must be qps or online.');
if (apiBase !== 'http://localhost:19382' || input.issuer !== issuer || input.projectId !== 'demo') invalid('Only this local development gateway/issuer/demo project is allowed.');
if (!Number.isInteger(users) || users < 1 || people.length < users) invalid('USERS requires at least that many distinct input identities.');
if (!Number.isInteger(rate) || rate < 1 || !Number.isInteger(durationSeconds) || durationSeconds < 1 || !Number.isInteger(readPercent) || readPercent < 0 || readPercent > 100) invalid('RATE/DURATION_SECONDS must be positive integers; READ_PERCENT must be 0..100.');
if (!Number.isFinite(onlinePeriod) || onlinePeriod <= 0) invalid('ONLINE_PERIOD_SECONDS must be positive.');
if (mode === 'online' && durationSeconds < onlinePeriod * 2) invalid('Online duration must cover at least two complete user cycles.');
if (!Array.isArray(seedAssetIds) || !seedAssetIds.length) invalid('Seed assets first; seedAssetIds is required.');

const successfulBusiness = new Counter('core_successful_business_requests');
const attemptedBusiness = new Counter('core_attempted_business_requests');
const businessSuccess = new Rate('core_business_success');
const readAttempts = new Counter('core_read_attempts');
const writeAttempts = new Counter('core_write_attempts');
const endpointError = new Rate('business_endpoint_error');
const listTime = new Trend('assets_list_ms', true);
const detailTime = new Trend('assets_detail_ms', true);
const createTime = new Trend('assets_create_ms', true);
const listError = new Rate('assets_list_error');
const detailError = new Rate('assets_detail_error');
const createError = new Rate('assets_create_error');
const onlineIdentity = new Rate('online_identity_valid');
const distinctOnlineSubjects = new Counter('online_subjects_validated');
const thresholds = {
  core_business_success: ['rate==1'],
  business_endpoint_error: ['rate==0'],
};
if (mode === 'qps') thresholds.dropped_iterations = ['count==0'];
else { thresholds.online_identity_valid = ['rate==1']; thresholds.online_subjects_validated = [`count==${users}`]; }

export const options = {
  scenarios: mode === 'qps' ? {
    business: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${durationSeconds}s`, preAllocatedVUs: Math.min(users, rate), maxVUs: users, exec: 'business', gracefulStop: '10s' },
  } : {
    authorized_users: { executor: 'constant-vus', vus: users, duration: `${durationSeconds}s`, exec: 'online', gracefulStop: '10s' },
  },
  setupTimeout: '180s',
  summaryTrendStats: ['avg', 'min', 'max', 'p(95)', 'p(99)'],
  thresholds,
};

function decodeToken(raw) {
  try { return JSON.parse(encoding.b64decode(raw.split('.')[1], 'rawurl', 's')); }
  catch (_) { invalid('A bearer token is malformed; its contents were suppressed.'); }
}
function headers(person) { return { Authorization: `Bearer ${person.accessToken}`, 'Content-Type': 'application/json' }; }
function json(response) { try { return response.json(); } catch (_) { return null; } }
function validPermissions(me) {
  const p = me?.projectPermissions?.demo || [];
  return p.includes('*') || (p.includes('asset.read') && (readPercent === 100 || p.includes('asset.write')));
}

export function setup() {
  const seen = new Set();
  const now = Math.floor(Date.now() / 1000);
  // Validate EVERY supplied token, including identities beyond USERS. Local decode is
  // only a precheck: auth/me validates issuer/signature/audience/current grants server-side.
  for (let index = 0; index < people.length; index++) {
    const person = people[index];
    if (!person || typeof person.accessToken !== 'string' || !person.accessToken) invalid(`Missing access token at input index ${index}.`);
    const claims = decodeToken(person.accessToken);
    if (claims.iss !== issuer || !claims.sub || claims.sub !== person.subject || seen.has(claims.sub)) invalid(`Invalid or duplicate token subject at input index ${index}.`);
    if (!Number.isFinite(claims.exp) || claims.exp < now + durationSeconds + 60) invalid('Tokens expire too soon for this window; regenerate the private token file or shorten the run.');
    seen.add(claims.sub);
    const response = http.get(apiBase + '/api/v1/auth/me', { headers: headers(person), tags: { phase: 'setup', name: 'identity_validation' }, timeout: '10s' });
    const me = json(response);
    const okay = check(response, { 'token_authorized_human_subject': () => response.status === 200 && me?.subject === person.subject && !me?.service && me?.projects?.includes('demo') && validPermissions(me) });
    if (!okay) invalid(`Input identity ${index} failed server authorization; response/token suppressed.`);
  }
  return { distinctValidatedUsers: seen.size, requestedUsers: users, mode, rate, durationSeconds, readPercent, startedAt: new Date().toISOString() };
}

function operation(person, iteration) {
  const read = ((iteration * 37) % 100) < readPercent;
  const opts = { headers: headers(person), timeout: '10s', tags: { phase: 'business', endpoint: read ? (iteration % 2 ? 'assets_list' : 'assets_detail') : 'assets_create' } };
  let response, okay;
  attemptedBusiness.add(1);
  if (read) {
    readAttempts.add(1);
    if (iteration % 2) {
      const page = 1 + (iteration % Math.max(1, Math.ceil((input.seedAssetRequestCount || seedAssetIds.length) / 20)));
      response = http.get(`${apiBase}/api/v1/assets?projectId=demo&source=LOAD_TEST&page=${page}&pageSize=20&sortBy=updatedAt&sortOrder=desc`, opts);
      const value = json(response);
      okay = response.status === 200 && Array.isArray(value?.items) && Number.isFinite(value?.total);
      listTime.add(response.timings.duration);
    } else {
      const id = seedAssetIds[iteration % seedAssetIds.length];
      response = http.get(`${apiBase}/api/v1/assets/${encodeURIComponent(id)}?projectId=demo`, opts);
      const value = json(response);
      okay = response.status === 200 && value?.id === id && value?.projectId === 'demo' && value?.mode === 'SIMULATED';
      detailTime.add(response.timings.duration);
    }
  } else {
    writeAttempts.add(1);
    response = http.post(apiBase + '/api/v1/assets', JSON.stringify({ projectId: 'demo', name: `load-write-${exec.vu.idInTest}-${iteration}-${Date.now()}`, type: 'SOFTWARE', category: 'LOAD_TEST', mode: 'SIMULATED', owner: 'load-fixture', technical: { loadTest: true } }), opts);
    const value = json(response);
    okay = response.status === 201 && !!value?.id && value?.mode === 'SIMULATED' && value?.resourceStatus === 'UNKNOWN';
    createTime.add(response.timings.duration);
  }
  businessSuccess.add(okay);
  endpointError.add(!okay, { endpoint: opts.tags.endpoint });
  if (opts.tags.endpoint === 'assets_list') listError.add(!okay);
  else if (opts.tags.endpoint === 'assets_detail') detailError.add(!okay);
  else createError.add(!okay);
  check(response, { authorized_business_result: () => okay });
  if (okay) successfulBusiness.add(1);
}

export function business() {
  // Arrival model exercises all identities over time, but is NOT evidence that
  // every preallocated VU is a distinct simultaneously active user.
  const iteration = exec.scenario.iterationInTest;
  operation(people[iteration % users], iteration);
}

export function online() {
  // This mode has exactly one scenario: each of its USERS VUs owns a different sub.
  const index = exec.vu.idInTest - 1;
  if (index < 0 || index >= users) invalid('Unexpected VU mapping; cannot assert distinct users.');
  const person = people[index];
  if (exec.vu.iterationInScenario === 0) {
    // Spread first requests across a cycle instead of firing a 200-request burst.
    sleep(index / rate);
  }
  const begin = Date.now();
  const response = http.get(apiBase + '/api/v1/auth/me', { headers: headers(person), tags: { phase: 'online_identity', name: 'online_identity' }, timeout: '10s' });
  const me = json(response);
  const valid = response.status === 200 && me?.subject === person.subject && !me?.service && me?.projects?.includes('demo');
  onlineIdentity.add(valid);
  if (!valid) invalid('An online identity lost effective authorization; token contents suppressed.');
  if (exec.vu.iterationInScenario === 0) distinctOnlineSubjects.add(1);
  operation(person, exec.scenario.iterationInTest);
  // Fixed users are a closed model: business throughput may fall below RATE when
  // services slow. Report the observed success rate instead of claiming 40 QPS.
  sleep(Math.max(0, onlinePeriod - (Date.now() - begin) / 1000));
}

export function handleSummary(data) {
  const successful = data.metrics.core_successful_business_requests?.values.count || 0;
  const attempted = data.metrics.core_attempted_business_requests?.values.count || 0;
  const summary = {
    mode, requestedDistinctUsers: users, configuredBusinessArrivalRate: mode === 'qps' ? rate : null,
    configuredWindowSeconds: durationSeconds, readPercent, writePercent: 100 - readPercent,
    attemptedBusinessRequests: attempted, successfulBusinessRequests: successful,
    successfulBusinessQpsOverConfiguredWindow: successful / durationSeconds,
    // Completion may extend into gracefulStop. Use the conservative actual run
    // window too, and retain raw metrics for independent timing analysis.
    actualRunSeconds: (data.state?.testRunDurationMs || durationSeconds * 1000) / 1000,
    successfulBusinessQpsOverActualRun: successful / ((data.state?.testRunDurationMs || durationSeconds * 1000) / 1000),
    readAttempts: data.metrics.core_read_attempts?.values.count || 0,
    writeAttempts: data.metrics.core_write_attempts?.values.count || 0,
    webpageResult: 'NOT_MEASURED: API p95 is not webpage operation-to-main-result latency',
    interpretation: 'Local development API exercise only; no formal SRS acceptance inferred.',
    raw: data,
  };
  return {
    [__ENV.SUMMARY_FILE || '/tmp/aidevops-assets-load-summary.json']: JSON.stringify(summary, null, 2),
    stdout: JSON.stringify({ mode, attempted, successful, actualWindowSuccessQps: summary.successfulBusinessQpsOverActualRun, webpageResult: summary.webpageResult }) + '\n',
  };
}
