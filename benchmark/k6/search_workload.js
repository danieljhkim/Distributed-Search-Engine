import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const baseUrl = __ENV.DSEARCH_GATEWAY_BASE_URL || 'http://localhost:8080';
const searchPath = __ENV.DSEARCH_GATEWAY_SEARCH_PATH || '/api/v1/search';
// k6 resolves open() relative to this script, not the caller's working directory.
const mixPath = __ENV.DSEARCH_BENCH_QUERY_MIX || '../config/query-mixes.json';
const mix = new SharedArray('representative-query-mix', () => [JSON.parse(open(mixPath))])[0];

function responseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function rankOf(hits, expectedDocId) {
  return (hits || []).findIndex((hit) => hit.docId === expectedDocId) + 1;
}

export function createSearchWorkload(searchType) {
  const queries = mix.representative[searchType];
  if (!queries || queries.length === 0) {
    throw new Error(`No representative queries configured for ${searchType}`);
  }
  const expectedFanout = Number(__ENV.DSEARCH_BENCH_EXPECTED_FANOUT || 1);
  const warmup = __ENV.DSEARCH_BENCH_WARMUP || '5s';
  const duration = __ENV.DSEARCH_BENCH_DURATION || '10s';

  return {
    options: {
      stages: [
        { duration: warmup, target: Number(__ENV.DSEARCH_BENCH_VUS || 2) },
        { duration, target: Number(__ENV.DSEARCH_BENCH_VUS || 2) },
        { duration: '0s', target: 0 },
      ],
      thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate==1'],
        http_req_duration: ['p(95)<1000'],
      },
    },
    execute: function () {
      const query = queries[(__VU + __ITER) % queries.length];
      const response = http.post(`${baseUrl}${searchPath}`, JSON.stringify({
        query: query.query,
        partitionId: __ENV.DSEARCH_BENCH_PARTITION_ID || 'bench',
        page: 0,
        pageSize: Number(__ENV.DSEARCH_BENCH_PAGE_SIZE || 10),
        searchType,
        highlight: false,
      }), { headers: { 'Content-Type': 'application/json' } });
      const body = responseJson(response);
      check(response, {
        [`${searchType}: HTTP 200`]: (r) => r.status === 200,
        [`${searchType}: response has hits`]: () => body && Array.isArray(body.hits),
        [`${searchType}: expected document is returned`]: () => rankOf(body && body.hits, query.expectedDocId) > 0,
        [`${searchType}: expected ranking bound`]: () => rankOf(body && body.hits, query.expectedDocId) <= query.maxRank,
        [`${searchType}: relevance total meets threshold`]: () => body && body.totalHits >= query.minTotalHits,
        [`${searchType}: fan-out metadata is successful`]: () => body && body.fanout
          && body.fanout.status === 'SUCCESS' && body.fanout.attemptedNodes >= expectedFanout
          && body.fanout.succeededNodes >= expectedFanout,
      });
    },
  };
}
