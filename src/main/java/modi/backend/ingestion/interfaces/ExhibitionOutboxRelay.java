package modi.backend.ingestion.interfaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.ExhibitionIngestionOrchestrator;
import modi.backend.ingestion.application.outbox.OutboxEnqueued;

/**
 * 전시 아웃박스 <b>릴레이</b> — 도래한(status·next_attempt_at ≤ now) 이벤트를 집어 파사드의 소비 매핑으로 넘긴다.
 * 트랜잭션 아웃박스의 "message relay" 역할이다(ADR-10).
 *
 * <p><b>두 개의 드레인 경로, 하나의 진실</b>:
 * <ol>
 *   <li><b>스케줄 폴링(durable 엔진)</b> — {@code poll-interval-ms} 주기로 테이블을 폴링한다. 재시작·크래시 후에도
 *       테이블에 남은 메시지를 이어서 처리하는 유일한 신뢰 경로다.</li>
 *   <li><b>커밋 직후 이벤트(글루)</b> — enqueue 트랜잭션이 커밋되면({@code AFTER_COMMIT}) 비동기로 즉시 드레인해
 *       폴링 주기 대기를 없앤다. 스프링 이벤트는 인메모리라 유실될 수 있지만, 그 유실은 ①이 줍는다 —
 *       <b>지연일 뿐 손실이 아니다</b>.</li>
 * </ol>
 * 두 경로가 같은 메시지를 동시에 집으면 낙관락(version)으로 한쪽만 이긴다(다른 쪽은 정상 skip).
 *
 * <p>이벤트 드레인은 전용 실행기({@code outboxRelayExecutor}: 스레드 1·대기 1·초과 폐기)로 코얼레싱한다 —
 * 한 번의 sync가 메시지를 수백 건 적재해도 드레인은 "진행 중 1 + 대기 1"로 뭉쳐진다(테이블이 진실이라
 * 몇 번을 드레인하든 결과는 같다).
 *
 * <p>각 드레인 실패는 서로를 막지 않도록 격리한다(하나가 던져도 다음 주기·다른 드레인은 계속). 스케줄 게이트는
 * 기존 보강 스케줄과 같은 플래그({@code app.exhibition.enrich.scheduling-enabled})를 쓴다(테스트에서 off).
 */
@Component
@ConditionalOnProperty(name = "app.exhibition.enrich.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExhibitionOutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionOutboxRelay.class);

	/** 이벤트 → 다음 스텝 매핑의 단일 소유자 — 릴레이는 도래분을 집어 파사드에 넘길 뿐이다. */
	private final ExhibitionIngestionOrchestrator ingestionOrchestrator;

	/**
	 * 로컬 시드 모드면 드레인도 건너뛴다. 시드는 메시지를 만들지 않아 빈 폴링만 돌아 무해하지만, "로컬은 외부 보강을 하지
	 * 않는다"는 의도를 명확히 하려고 명시적으로 skip한다(정기 동기화 스케줄러와 동일 게이트).
	 */
	@Value("${app.local-seed.enabled:false}")
	private boolean localSeedEnabled;

	/**
	 * durable 엔진 — 주기 폴링. 재시작 후에도 테이블에 남은 메시지가 이어서 처리된다(at-least-once).
	 * 폴링도 이벤트 드레인과 <b>같은 실행기</b>로 제출해 인프로세스 드레인을 단일 직렬화한다 — 두 경로가 같은
	 * 배치를 동시에 집으면 낙관락으로 정합성은 지켜지지만 AI 호출(유료·한도)이 통째로 중복되기 때문.
	 */
	@Async("outboxRelayExecutor")
	@Scheduled(fixedDelayString = "${app.exhibition.outbox.poll-interval-ms:60000}",
			initialDelayString = "${app.exhibition.outbox.poll-interval-ms:60000}")
	public void poll() {
		drain();
	}

	/** 글루 — enqueue 커밋 직후 비동기 드레인(폴링 대기 제거). 유실돼도 {@link #poll()}이 줍는다. */
	@Async("outboxRelayExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onEnqueued(OutboxEnqueued event) {
		drain();
	}

	/** 도래한 이벤트 전 타입 드레인 — 장르(DETAIL_FETCHED) → 상세(DRAFT_STAGED) → 승격(DRAFT_READY) → 영업시간 재검증(PLACE_HOURS_STALE). */
	public void drain() {
		if (localSeedEnabled) {
			return; // 로컬 시드 모드 — 외부 보강 드레인 안 함(로그 폭주 방지: 60초 폴링이라 침묵).
		}
		try {
			ingestionOrchestrator.drainGenreClassification();
		} catch (RuntimeException e) {
			log.warn("장르 분류 드레인 실패(다음 주기 재시도): {}", e.getMessage());
		}
		try {
			ingestionOrchestrator.drainDetailFetch();
		} catch (RuntimeException e) {
			log.warn("상세 재시도 드레인 실패(다음 주기 재시도): {}", e.getMessage());
		}
		try {
			ingestionOrchestrator.drainPromotion();
		} catch (RuntimeException e) {
			log.warn("승격 드레인 실패(다음 주기 재시도): {}", e.getMessage());
		}
		try {
			ingestionOrchestrator.drainPlaceHours();
		} catch (RuntimeException e) {
			log.warn("영업시간 재검증 드레인 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}
}
