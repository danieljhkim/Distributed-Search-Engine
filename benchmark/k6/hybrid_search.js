import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.DSEARCH_GATEWAY_BASE_URL || 'http://localhost:8080';
const SEARCH_PATH = __ENV.DSEARCH_GATEWAY_SEARCH_PATH || '/api/v1/search';

export const options = {
  vus: Number(__ENV.DSEARCH_BENCH_VUS || 5),
  duration: __ENV.DSEARCH_BENCH_DURATION || '15s',
  thresholds: {
    http_req_duration: [
      'p(50)<150',   // keep loose for semantic
      'p(95)<500',
      'p(99)<1000',
    ],
  },
};

export default function () {
  const url = `${BASE_URL}${SEARCH_PATH}`;

  const payload = JSON.stringify({
    query: __ENV.DSEARCH_BENCH_QUERY || 'hola amigo, hello world',
    page: Number(__ENV.DSEARCH_BENCH_PAGE || 0),
    pageSize: Number(__ENV.DSEARCH_BENCH_PAGE_SIZE || 30),
    searchType: 'HYBRID',
    shardId: '0',
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 200': r => r.status === 200,
  });
}