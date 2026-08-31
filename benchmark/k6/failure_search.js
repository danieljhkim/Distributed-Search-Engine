import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.DSEARCH_GATEWAY_BASE_URL || 'http://localhost:8080';
const searchPath = __ENV.DSEARCH_GATEWAY_SEARCH_PATH || '/api/v1/search';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

export default function () {
  const response = http.post(`${baseUrl}${searchPath}`, JSON.stringify({ query: 'failure evidence', partitionId: __ENV.DSEARCH_BENCH_PARTITION_ID || 'bench', page: 0, pageSize: 10, searchType: 'BM25' }), { headers: { 'Content-Type': 'application/json' } });
  let body;
  try { body = response.json(); } catch (_) { body = null; }
  check(response, {
    'failure preserves an explicit error or partial response': (r) => r.status === 200 || r.status === 503,
    'failure exposes partial fan-out or structured unavailability': () => (body && body.fanout && body.fanout.status === 'PARTIAL_FAILURE') || (body && (body.error || body.message || body.code || body.status)),
  });
}
