package modi.backend.ingestionv2.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.outbox.OutboxService;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.common.queue.StreamStatus;
import modi.backend.ingestionv2.common.queue.StreamStatusReader;

/**
 * 적체량 계기 - 카운터가 답하지 못하는 "지금 얼마나 밀려 있는가"를 맡는다.
 *
 * <ul>
 *   <li>미처리 목록(PEL)과 미발행 행 수 둘 - 전자는 소비 적체, 후자는 발행 적체</li>
 *   <li>읽기는 짧은 시간 동안 재사용한다 - 계기 하나가 스크레이프마다 대기열과 DB 를 따로 두드리면
 *       관측이 관측 대상을 바꾼다</li>
 *   <li>슬라이스를 끄면 등록되지 않는다 - 꺼진 슬라이스의 대기열을 스크레이프마다 두드릴 이유가 없다</li>
 *   <li>조회 실패를 삼키고 직전 값을 유지한다 - 계기 하나 때문에 스크레이프 전체가 실패하지 않게</li>
 *   <li>계기는 스크레이프 주기보다 짧은 봉우리를 놓친다 - 순간 최댓값 주장은 카운터 시계열로 한다</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = "enabled", havingValue = "true")
public class IngestionMetricsBinder {

	/** 대기열별 미처리 항목 수. */
	public static final String STREAM_PENDING_GAUGE = "ingestion.stream.pending";

	/** 미발행 아웃박스 행 수. */
	public static final String OUTBOX_PENDING_GAUGE = "ingestion.outbox.pending";

	/** 읽기 재사용 창. 실험 하네스의 1초 표본에 맞춘 값이다. */
	private static final long CACHE_MILLIS = 1_000L;

	private final StreamStatusReader streamStatusReader;
	private final OutboxService outboxService;

	private final Map<String, Long> pendingByStream = new HashMap<>();
	private volatile long streamReadAt;
	private volatile long outboxPending;
	private volatile long outboxReadAt;

	public IngestionMetricsBinder(StreamStatusReader streamStatusReader, OutboxService outboxService,
			MeterRegistry meterRegistry) {
		this.streamStatusReader = streamStatusReader;
		this.outboxService = outboxService;
		for (IngestionStream stream : IngestionStream.values()) {
			Gauge.builder(STREAM_PENDING_GAUGE, this, binder -> binder.pendingOf(stream.key()))
					.tag("stream", stream.key())
					.register(meterRegistry);
		}
		Gauge.builder(OUTBOX_PENDING_GAUGE, this, IngestionMetricsBinder::outboxPending)
				.register(meterRegistry);
	}

	private double pendingOf(String streamKey) {
		refreshStreams();
		synchronized (pendingByStream) {
			return pendingByStream.getOrDefault(streamKey, 0L);
		}
	}

	private double outboxPending() {
		if (System.currentTimeMillis() - outboxReadAt < CACHE_MILLIS) {
			return outboxPending;
		}
		try {
			outboxPending = outboxService.countPending();
		} catch (RuntimeException unavailable) {
			log.warn("미발행 행 수를 읽지 못했습니다. 직전 값을 유지합니다.", unavailable);
		}
		outboxReadAt = System.currentTimeMillis();
		return outboxPending;
	}

	private void refreshStreams() {
		if (System.currentTimeMillis() - streamReadAt < CACHE_MILLIS) {
			return;
		}
		try {
			List<StreamStatus> statuses = streamStatusReader.readAll();
			synchronized (pendingByStream) {
				statuses.forEach(status -> pendingByStream.put(status.streamKey(), status.pendingCount()));
			}
		} catch (RuntimeException unavailable) {
			log.warn("대기열 상태를 읽지 못했습니다. 직전 값을 유지합니다.", unavailable);
		}
		streamReadAt = System.currentTimeMillis();
	}
}
