#!/usr/bin/env python3
"""run-push.sh 의 봉인 절. 표본 파일을 되읽어 구간을 나누고 run-summary.{json,md} 를 쓴다.

구간 경계를 스크립트가 기억한 값이 아니라 표본에서 되읽는 이유는 하나다.
app3 는 부팅이 끝나야 합류하므로 "기동을 요청한 시각"과 "실제로 배분을 받기 시작한 시각"이 다르다.
경계는 후자여야 하고, 그것을 아는 것은 소비 카운터가 처음 0을 넘은 표본뿐이다.
"""
import glob
import json
import os
import re

ATT = os.environ["ATT"]
OUT = os.environ["OUT"]

SUCCESS = re.compile(r'^ingestion_consume_seconds_count\{.*result="success"')


def env(name, default=""):
    value = os.environ.get(name, "")
    return value if value != "" else default


def num(name, default=0):
    value = env(name, "")
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def series():
    """앱별 (표본 시각, 소비 성공 누적) 목록."""
    out = {}
    for path in sorted(glob.glob(os.path.join(ATT, "app*", "prom-*.txt"))):
        app = os.path.basename(os.path.dirname(path))
        stamp = int(re.search(r"prom-(\d+)\.txt", path).group(1))
        total = 0.0
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if SUCCESS.match(line):
                    total += float(line.rsplit(None, 1)[1])
        out.setdefault(app, []).append((stamp, int(total)))
    for app in out:
        out[app].sort()
    return out


def value_at(points, stamp):
    """그 시각까지의 마지막 표본값. 강제 종료된 앱은 마지막 값이 그대로 이어진다."""
    value = 0
    for at, seen in points:
        if at <= stamp:
            value = seen
        else:
            break
    return value


def phase(name, start, end, data, baseline_zero=False):
    """구간의 소비 델타. 첫 구간의 기준선은 0이다 - 앱이 뜬 순간의 카운터가 0이고,
    첫 표본이 이미 T0 에 찍혔더라도 그 사이의 처리는 이 구간에서 일어난 일이기 때문이다."""
    seconds = max(end - start, 0)
    by_app = {app: value_at(points, end) - (0 if baseline_zero else value_at(points, start))
              for app, points in data.items()}
    consumed = sum(by_app.values())
    return {
        "name": name,
        "from": start,
        "to": end,
        "seconds": seconds,
        "consumed": consumed,
        "per_minute": round(consumed * 60 / seconds, 1) if seconds > 0 else 0.0,
        "by_app": by_app,
        "app3_share_percent": round(by_app.get("app3", 0) * 100 / consumed, 1) if consumed > 0 else 0.0,
    }


data = series()
t0 = num("RUN_STARTED")
last_sample = max((points[-1][0] for points in data.values() if points), default=t0)
app3_points = data.get("app3", [])
app3_first = next((at for at, seen in app3_points if seen > 0), 0)
# 구간 경계는 app3 가 처음 0을 넘은 표본이 아니라 그 직전 표본이다.
#   두 표본 사이 어딘가에서 합류했으므로 그 구간을 2대 쪽에 넣으면 2대 값에 app3 몫이 섞인다.
#   3대 쪽에 넣으면 3대 구간이 조금 길어져 app3 비중이 낮게 나오는데, 판정이 보수적으로 기울 뿐 과장되지 않는다.
app3_join = max((at for at, seen in app3_points if seen == 0 and at < app3_first), default=t0) if app3_first else 0
app3_kill = num("APP3_KILL_AT")

phases = []
if app3_first:
    phases.append(phase("apps-2", t0, app3_join, data, baseline_zero=True))
    phases.append(phase("apps-3", app3_join, app3_kill or last_sample, data))
    if app3_kill:
        phases.append(phase("apps-2-after-reclaim", app3_kill, last_sample, data))
else:
    phases.append(phase("apps-2", t0, last_sample, data, baseline_zero=True))

by_app = {}
for token in env("CONSUME_BY_APP").split():
    if "=" in token:
        key, value = token.split("=", 1)
        by_app[key] = int(float(value))

consume_success = num("CONSUME_SUCCESS")
unique = num("CONSUMED_UNIQUE")
duplicates = num("CONSUMED_DUP")
rows = num("ROWS_PER_MINUTE") * max(num("SEED_MINUTES", 1), 1)
cluster = env("CLUSTER_MODE", "single") == "cluster"

summary = {
    "step": env("VARIANT"),
    "variant": "after",
    "metric": "consume_success_total",
    "unit": "건",
    "aggregation": {
        "consume_success_total": consume_success,
        "consumed_unique": unique,
        "duplicates": duplicates,
    },
    "runs": [{"i": 1, "value": consume_success, "failed": False}],
    "warmup_excluded": 0,
    "measurements": {
        "consume_success_total": consume_success,
        "consume_total": num("CONSUME_TOTAL"),
        "consume_by_app": by_app,
        "consumed_unique": unique,
        "consumed_handle_calls": num("CONSUMED_COUNT"),
        "duplicates": duplicates,
        "xpending_end": num("XPENDING_END"),
        "xlen_stream": num("XLEN_STREAM"),
        "reclaim_claimed": num("RECLAIM_CLAIMED"),
        "reclaim_skipped": num("RECLAIM_SKIPPED"),
        "app3_pending_at_kill": num("APP3_PENDING"),
        "publish_success": num("PUBLISH_SUCCESS"),
        "publish_failure": num("PUBLISH_FAILURE"),
        "claim_calls": num("CLAIM_CALLS"),
        "claim_rows": num("CLAIM_ROWS"),
        "marker_acquired": num("MARKER_ACQUIRED"),
        "marker_skipped": num("MARKER_SKIPPED"),
        "outbox_rows": num("TOTAL_ROWS"),
        "outbox_sent": num("SENT_ROWS"),
        "outbox_pending": num("PENDING_ROWS"),
        "outbox_failed": num("FAILED_ROWS"),
        "external_api_calls_ingestion": num("EXTERNAL_CALLS"),
        "com_select_delta": num("COM_SELECT"),
        "innodb_rows_read_delta": num("ROWS_READ"),
        "com_commit_delta": num("COM_COMMIT"),
        "innodb_row_lock_waits_delta": num("LOCK_WAITS"),
        "elapsed_seconds": num("ELAPSED"),
        "seed_seconds": num("SEED_SECONDS"),
        "drain_seconds": num("DRAIN_SECONDS", -1),
        "consume_per_minute_by_phase": phases,
        "phase_boundaries": {
            "t0_run_started": t0,
            "app3_requested_at": num("APP3_START_AT"),
            "app3_first_positive_sample": app3_first,
            "app3_phase_boundary": app3_join,
            "app3_killed_at": app3_kill,
            "app3_last_scrape_at": num("APP3_LAST_SCRAPE_AT"),
            "last_sample_at": last_sample,
        },
    },
    "condition": {
        "mode": env("MODE"),
        "seed_mode": "burst(전량을 먼저 넣고 앱을 띄운다)",
        "rows": rows,
        "event_type": env("SEED_EVENT_TYPE", "COLLECTED"),
        "stream": env("STREAM"),
        "consumer_group": env("GROUP"),
        "consume_handler": env("INGESTION_CONSUME_HANDLER", "REAL"),
        "stub_latency_ms": num("INGESTION_STUB_LATENCY_MS"),
        "instances": "L3:app=2→3→2" if app3_first else "L3:app=%s" % env("INSTANCES", "2"),
        "app3_heap": "-Xmx1g(app1·app2 는 -Xmx2g)" if app3_first else None,
        "tick_interval_ms": num("INGESTION_DISPATCH_INTERVAL_MS"),
        "batch": num("INGESTION_DISPATCH_BATCH_SIZE"),
        "claim_strategy": env("INGESTION_CLAIM_STRATEGY"),
        "outbox_read": env("INGESTION_OUTBOX_READ"),
        "external_stream_consumers": 2,
        "read_batch_size": 10,
        "reclaim_idle_seconds": num("INGESTION_RECLAIM_IDLE_SECONDS", 60),
        "reclaim_interval_ms": num("INGESTION_RECLAIM_INTERVAL_MS", 30000),
        "redis": ("cluster 마스터 3·복제 0 (%s) / %s" % (env("CLUSTER_NODES"), env("REDIS_VERSION")))
        if cluster else ("단일 노드 / %s" % env("REDIS_VERSION")),
        "mysql": env("MYSQL_VERSION"),
        "tmax_seconds": num("TMAX_SECONDS"),
        "idle_samples": num("IDLE_SAMPLES"),
        "scrape_samples": num("SAMPLES"),
        "trim_cron": "-", "cleanup_cron": "-", "collect_cron": "-",
        "core_schedulers": "disabled(sync·relay·purge·cache-warm·view-count-flush·remind-backfill / watchdog 24h)",
        "commit": env("COMMIT"),
        "measured_at": env("MEASURED_AT"),
        "app_started_at": env("STARTED_AT"),
        "apps_up_at": env("APPS_UP_AT"),
        "before_check": "attachments/before-check.txt",
    },
    "attachments": sorted(
        os.path.join("attachments", os.path.relpath(path, ATT))
        for path in glob.glob(os.path.join(ATT, "*"))
        if os.path.isfile(path)
    ),
}

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "run-summary.json"), "w", encoding="utf-8") as handle:
    json.dump(summary, handle, ensure_ascii=False, indent=2)
    handle.write("\n")

lines = [
    "# %s — Push(Redis Streams 소비) · 스텁 %sms · %s"
    % (env("VARIANT"), num("INGESTION_STUB_LATENCY_MS"), summary["condition"]["instances"]),
    "",
    "측정 %s · 커밋 `%s` · 모드 %s(%s행 한 번에 적재 후 앱 기동, 관측 상한 %ss · 정지 %s표본 and XPENDING 0)"
    % (env("MEASURED_AT"), env("COMMIT"), env("MODE"), rows, num("TMAX_SECONDS"), num("IDLE_SAMPLES")),
    "",
    "| 지표 | 값 |",
    "|---|---|",
    "| 소비 성공 합(주 지표) | %s |" % consume_success,
    "| 앱별 소비 성공 | %s |" % (" / ".join("%s %s" % (k, v) for k, v in sorted(by_app.items())) or "-"),
    "| 고유 처리 키(SCARD lab:consumed:ids) | %s |" % unique,
    "| 중복 소비(GET lab:consumed:dup) | %s |" % duplicates,
    "| 처리 호출 수(GET lab:consumed:count) | %s |" % num("CONSUMED_COUNT"),
    "| 종료 XPENDING(4스트림 합) | %s |" % num("XPENDING_END"),
    "| XLEN %s | %s |" % (env("STREAM"), num("XLEN_STREAM")),
    "| 회수 claimed / skipped | %s / %s |" % (num("RECLAIM_CLAIMED"), num("RECLAIM_SKIPPED")),
    "| kill 직후 app3 미처리 | %s |" % num("APP3_PENDING"),
    "| 발행 성공 / 실패 | %s / %s |" % (num("PUBLISH_SUCCESS"), num("PUBLISH_FAILURE")),
    "| 선점 호출 / 선점 행수 | %s / %s |" % (num("CLAIM_CALLS"), num("CLAIM_ROWS")),
    "| 마커 획득 / 건너뜀 | %s / %s |" % (num("MARKER_ACQUIRED"), num("MARKER_SKIPPED")),
    "| 아웃박스 (총 / SENT / PENDING / FAILED) | %s / %s / %s / %s |"
    % (num("TOTAL_ROWS"), num("SENT_ROWS"), num("PENDING_ROWS"), num("FAILED_ROWS")),
    "| 외부 API 호출 감사(source='INGESTION') | %s |" % num("EXTERNAL_CALLS"),
    "| 적재(초) / 소진(초) / 관측 경과(초) / 표본 수 | %s / %s / %s / %s |"
    % (num("SEED_SECONDS"), num("DRAIN_SECONDS", -1), num("ELAPSED"), num("SAMPLES")),
    "",
    "## 구간별 소비",
    "",
    "| 구간 | 시작(epoch) | 끝(epoch) | 초 | 소비 | 분당 | 앱별 | app3 비중 |",
    "|---|---|---|---|---|---|---|---|",
]
for item in phases:
    lines.append("| %s | %s | %s | %s | %s | %s | %s | %s%% |" % (
        item["name"], item["from"], item["to"], item["seconds"], item["consumed"], item["per_minute"],
        " ".join("%s=%s" % (k, v) for k, v in sorted(item["by_app"].items())),
        item["app3_share_percent"]))
lines += [
    "",
    "구간 경계는 표본에서 되읽은 값이다. app3 합류 경계는 기동을 요청한 시각이 아니라 소비 카운터가 처음 0을 넘은 표본의"
    " 바로 앞 표본이다(요청 %s · 첫 양수 표본 %s · 경계 %s · 강제 종료 %s · 마지막 표본 %s)."
    % (num("APP3_START_AT"), app3_first, app3_join, app3_kill, last_sample),
    "",
    "조건: MySQL %s · Redis %s · 컨슈머 ON(핸들러 %s, 지연 %sms) · 틱 %sms · 배치 %s · 회수 방치 판정 %ss."
    % (env("MYSQL_VERSION"), summary["condition"]["redis"], env("INGESTION_CONSUME_HANDLER", "REAL"),
       num("INGESTION_STUB_LATENCY_MS"), num("INGESTION_DISPATCH_INTERVAL_MS"),
       num("INGESTION_DISPATCH_BATCH_SIZE"), num("INGESTION_RECLAIM_IDLE_SECONDS", 60)),
    "원문은 `attachments/`.",
    "",
]
with open(os.path.join(OUT, "run-summary.md"), "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines))
