package modi.backend.interfaces.remind;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.remind.RemindSummaryBackfill;

/**
 * 리마인드 감정 변화 AI 요약 백필 스케줄러.
 * <p>
 * 요약은 저장 응답을 막지 않으려고 인메모리 스레드풀에서 만든다(PENDING → READY). 그래서 <b>요약 도중 서버가
 * 재시작·배포되면 그 작업이 유실되어 PENDING이 영구히 남을 수 있다</b> — 이 잡이 주기적으로 훑어 복구한다.
 * <p>
 * 유료 AI 호출이라 1회 실행량을 batch-size로 제한하고, 유예(grace) 이전 건만 대상으로 해 진행 중인 작업과 겹치지 않게 한다.
 * give-up-after를 넘긴 건은 호출 없이 FAILED로 확정해 클라이언트의 무한 폴링을 막는다.
 * AI 미설정이면 저장 시점에 이미 SKIPPED로 확정되므로 이 잡은 아무 것도 하지 않는다(외부 호출 0).
 */
@Component
@ConditionalOnProperty(name = "app.remind.summary-backfill.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RemindSummaryBackfillScheduler {

	private static final Logger log = LoggerFactory.getLogger(RemindSummaryBackfillScheduler.class);

	private final RemindSummaryBackfill remindSummaryBackfill;

	/** 1회 실행에서 처리할 최대 건수(유료 AI 호출량 상한). */
	@Value("${app.remind.summary-backfill.batch-size:20}")
	private int batchSize;

	/** 이 시간보다 오래된 PENDING만 대상 — 방금 저장돼 진행 중인 백그라운드 작업과 중복 호출 방지. */
	@Value("${app.remind.summary-backfill.grace:PT2M}")
	private Duration grace;

	/** 이 시간을 넘겨도 PENDING이면 FAILED로 확정(영구 PENDING·무한 폴링 방지). */
	@Value("${app.remind.summary-backfill.give-up-after:P1D}")
	private Duration giveUpAfter;

	/** 주기적으로 유실된 요약을 복구한다. 실패해도 다음 주기에 재시도. */
	@Scheduled(fixedDelayString = "${app.remind.summary-backfill.interval:PT10M}",
			initialDelayString = "${app.remind.summary-backfill.initial-delay:PT2M}")
	public void runBackfill() {
		try {
			int resolved = remindSummaryBackfill.backfillPending(batchSize, grace, giveUpAfter);
			if (resolved > 0) {
				log.info("리마인드 감정 변화 요약 백필 {}건 처리", resolved);
			}
		} catch (RuntimeException e) {
			log.warn("리마인드 요약 백필 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}
}
