// R5 — 거리순(S4) 격리 런. 한 요청이 수십 초라 다른 경로와 섞으면 그쪽 큐잉을 오염시킨다.
// searchAll 전량 로드 + findAllByIds 대량 IN 절이라 큰 볼륨에서는 에러가 정상 결과일 수 있다.
import exec from 'k6/execution';
import { rngOf, call, TREND_STATS } from './paths.js';

const SEED = Number(__ENV.SEED || 20260728);
const N = Number(__ENV.N || 50);

export const options = {
	summaryTrendStats: TREND_STATS,
	scenarios: {
		distance: {
			executor: 'shared-iterations',
			vus: 1,
			iterations: N,
			maxDuration: __ENV.MAX_DURATION || '30m',
		},
	},
};

export default function () {
	call('S4', rngOf(SEED + exec.scenario.iterationInTest));
}

export function handleSummary(data) {
	const out = { run: 'r5', vus: 1, iterations: N, metrics: {} };
	for (const [k, v] of Object.entries(data.metrics)) {
		if (k === 'lat_S4' || k === 'http_req_duration' || k === 'http_reqs'
			|| k === 'path_errors' || k === 'http_req_failed') {
			out.metrics[k] = v.values;
		}
	}
	const res = {};
	res[__ENV.OUT || 'k6-r5.json'] = JSON.stringify(out, null, 2);
	res.stdout = `\n[r5 distance] n=${N} fails=${(data.metrics.http_req_failed || {}).values?.passes ?? '?'}\n`;
	return res;
}
