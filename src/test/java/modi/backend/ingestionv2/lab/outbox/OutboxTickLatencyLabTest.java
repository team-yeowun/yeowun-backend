package modi.backend.ingestionv2.lab.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.ingestionv2.common.queue.EventDispatcher;

/**
 * step-04(유효 틱 주기 - "폴링 주기 대비 3배 지연" 가설의 대체 측정) 하네스.
 *
 * <ul>
 *   <li>{@code OutboxDispatcher.dispatchPending()} 를 수동으로 한 틱 호출해 잰다(스케줄러 빈은 미등록)</li>
 *   <li>{@link EventDispatcher} 를 no-op 스텁으로 바꾼다 - XADD 가 빠지므로 이 수치는 <b>DB 구간만</b>의
 *       틱 비용이다. 그 사실을 조건({@code event_dispatcher})에 적어 두었으니 문서가 넘겨 읽으면 안 된다</li>
 *   <li>발행은 롤백되지 않는다(상태가 SENT 로 바뀐다) - 매 회 뒤에 선점 대상 1,000행을 PENDING 으로 되돌린다</li>
 *   <li>유효 틱 주기 = dispatch-interval-ms + 틱 소요 p95. before/after 비율 3.0 이 가설의 "300%"</li>
 *   <li>합격 기준 없음(관측 보고). 미달이면 "이 규모에서는 재현되지 않음"으로 적는다</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.outbox.*"}
 */
@Tag("manual")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxTickLatencyLabTest extends OutboxLabSupport {

	/** XADD 없이 아무것도 하지 않는다. 틱에서 Redis 왕복을 걷어내 DB 구간만 남기려는 것. */
	@MockitoBean
	private EventDispatcher eventDispatcher;

	@AfterAll
	void restore() {
		closeLab();
	}

	@Test
	@DisplayName("step-04 — 인덱스 유무로 발송 한 틱의 소요와 유효 틱 주기를 잰다")
	void 발송_한_틱의_소요와_유효_틱_주기를_잰다() {
		List<OutboxLabScale> scales = OutboxLabConfig.scales();
		OutboxLabScale scale = scales.get(scales.size() - 1);
		prepareLab(scale.totalRows());

		OutboxLabRaw before = new OutboxLabRaw("step-04", "before");
		OutboxLabRaw after = new OutboxLabRaw("step-04", "after");

		long intervalMillis = Long.parseLong(
				context.getEnvironment().getProperty("app.ingestion.v2.dispatch-interval-ms", "1000"));
		Map<String, Object> base = baseCondition();
		base.put("dispatch_interval_ms", intervalMillis);
		base.put("dispatch_interval_source", "application.yaml app.ingestion.v2.dispatch-interval-ms "
				+ "(코드 기본값 OutboxDispatchScheduler:35 의 :1000 과 같은 값)");
		base.put("event_dispatcher", "no-op 스텁(@MockitoBean) - XADD 없음. 이 틱 소요는 DB 구간만이다");
		base.put("instances", "L1(스케줄러 빈 미등록, 수동 틱, 단일 스레드)");
		base.put("row_reset_after_each_run",
				"UPDATE ingestion_outbox SET status='PENDING', sent_at=NULL, retry_count=0 WHERE id IN (선점 대상 1,000행)");
		before.baseCondition(base);
		after.baseCondition(base);
		before.attach("scheduler-beans.txt", schedulerBeanReport());
		after.attach("scheduler-beans.txt", schedulerBeanReport());
		assertThat(schedulersUnregistered())
				.as("auto-delivery=false 인데 스케줄러 빈이 떠 있으면 자동 틱이 측정을 오염시킨다")
				.isTrue();

		OutboxLabSeeder.SeedResult seed = seeder.ensureScale(scale);
		Map<String, Object> scaleCondition = scaleCondition(seed);
		scaleCondition.put("pending_rows", OutboxLabScale.PENDING_ROWS);
		List<Long> pendingIds = seeder.pendingIds();

		// ── before: 인덱스 제거 ──────────────────────────────────────────────────
		before.attach("show-index-1-before-drop.txt", indexSwitch.showIndex());
		double dropMillis = indexSwitch.dropCurrent();
		jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
		before.attach("show-index-2-after-drop.txt", indexSwitch.showIndex());
		before.note("DROP INDEX %s 소요 %.1f ms".formatted(OutboxLabIndexSwitch.CURRENT_INDEX, dropMillis));
		double[] beforeTicks = repeat(() -> tickMillis(pendingIds));
		before.series("%s-dispatch-tick".formatted(scale), "dispatch_tick_latency_ms", "ms", beforeTicks,
				scaleCondition);

		// ── after: 인덱스 복구 ───────────────────────────────────────────────────
		double createMillis = indexSwitch.createCurrent();
		jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
		after.attach("show-index-3-after-create.txt", indexSwitch.showIndex());
		after.note("CREATE INDEX %s %s 소요 %.1f ms".formatted(OutboxLabIndexSwitch.CURRENT_INDEX,
				OutboxLabIndexSwitch.CURRENT_COLUMNS, createMillis));
		double[] afterTicks = repeat(() -> tickMillis(pendingIds));
		after.series("%s-dispatch-tick".formatted(scale), "dispatch_tick_latency_ms", "ms", afterTicks,
				scaleCondition);

		// ── 파생 지표 ───────────────────────────────────────────────────────────
		OutboxLabStats beforeStats = OutboxLabStats.of(beforeTicks);
		OutboxLabStats afterStats = OutboxLabStats.of(afterTicks);
		double beforePeriod = intervalMillis + beforeStats.p95();
		double afterPeriod = intervalMillis + afterStats.p95();
		Map<String, Object> derived = new LinkedHashMap<>();
		derived.put("effective_tick_period_ms_before", OutboxLabStats.round(beforePeriod));
		derived.put("effective_tick_period_ms_after", OutboxLabStats.round(afterPeriod));
		derived.put("ratio_before_over_after", afterPeriod == 0 ? null
				: OutboxLabStats.round(beforePeriod / afterPeriod));
		derived.put("readme_target_ratio", 3.0);
		derived.put("formula", "유효 틱 주기 = dispatch-interval-ms(%d) + 틱 소요 p95".formatted(intervalMillis));
		derived.put("scope_limit", "이 지표는 아웃박스 발송 틱 하나만 본다. 소비는 Redis Streams 블로킹 조회라 "
				+ "폴링 주기에 종속되지 않는다 - '파이프라인 전체 지연'으로 넓혀 쓰면 안 된다.");
		before.observation("effective-tick-period", derived);
		after.observation("effective-tick-period", derived);

		before.finish();
		after.finish();
		assertThat(after.directory().resolve("run-summary.json")).exists();
	}

	/** 한 틱. 끝나면 선점된 행을 미발행으로 되돌려 다음 회가 같은 상태에서 돌게 한다. */
	private double tickMillis(List<Long> pendingIds) {
		long startedAt = System.nanoTime();
		outboxDispatcher.dispatchPending();
		double elapsed = (System.nanoTime() - startedAt) / 1_000_000d;
		seeder.resetPendingRows(pendingIds);
		return elapsed;
	}
}
