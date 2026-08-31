import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.DSEARCH_GATEWAY_BASE_URL || 'http://localhost:8080';
const searchPath = __ENV.DSEARCH_GATEWAY_SEARCH_PATH || '/api/v1/search';
const expectedStatuses = (__ENV.DSEARCH_BENCH_OVERLOAD_STATUSES || '429,503').split(',').map(Number);

export const options = {
  vus: Number(__ENV.DSEARCH_BENCH_VUS || 32),
  duration: __ENV.DSEARCH_BENCH_DURATION || '10s',
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const response = http.post(`${baseUrl}${searchPath}`, JSON.stringify({ query: 'overload control', partitionId: __ENV.DSEARCH_BENCH_PARTITION_ID || 'bench', page: 0, pageSize: 10, searchType: 'BM25' }), { headers: { 'Content-Type': 'application/json' } });
  let body;
  try { body = response.json(); } catch (_) { body = null; }
  check(response, {
    'overload is shed with an expected status': (r) => expectedStatuses.includes(r.status),
    'overload response has structured error semantics': () => body && (body.error || body.message || body.code || body.status),
  });
}
