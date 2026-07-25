package modi.backend.ingestion.interfaces;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.admin.IngestionAdminFacade;
import modi.backend.ingestion.application.admin.IngestionAdminResult;

/**
 * 아웃박스 <b>주간 정리</b> 스케줄러(설계 §9) — 보존 기간(기본 7일)을 넘긴 SUCCEEDED 행을 소량 배치로 삭제한다.
 * 트리거일 뿐 정책(보존 기간·배치 크기·루프)은 전부 {@link IngestionAdminFacade#purgeSucceeded}가 소유한다
 * (의존 규칙 — 인터페이스는 조합자만 호출).
 *
 * <p><b>왜 지우는가/왜 이 방식인가(100만 건 실험 §9)</b>: 인덱스 덕에 비대가 선별 성능을 해치진 않지만,
 * 저장·백업 낭비와 대시보드 노이즈를 막는 위생 작업이다. 소량 배치(배치당 tx)인 이유는 대량 일괄 DELETE가
 * 삭제 마크·통계 왜곡으로 오히려 일시 악화(실측 30배)를 만들기 때문. 주간 삭제량이 수백 행 수준이라
 * OPTIMIZE 재구축은 불필요하다(퍼지가 자연 소화). FAILED_PERMANENT는 지우지 않는다(감사·수동 재시도 재료).
 *
 * <p>기본 매주 일요일 03시 — 01시 수집·즉시 소비 burst가 끝난 뒤의 한가한 시간대. 다중 인스턴스에선
 * 분산락으로 단일 노드 실행이 전제다(다른 스케줄러와 동일). 겹치더라도 DELETE LIMIT는 멱등적으로 안전하다.
 */
@Component
@ConditionalOnProperty(name = "app.exhibition.enrich.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExhibitionOutboxCleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionOutboxCleanupScheduler.class);

	private final IngestionAdminFacade ingestionAdminFacade;

	/** 매주 일요일 03시: 보존 기간 경과 SUCCEEDED 소량 배치 삭제. 실패해도 다음 주기에 재시도. */
	@Scheduled(cron = "${app.exhibition.outbox.purge-cron:0 0 3 * * SUN}")
	public void purgeWeekly() {
		try {
			IngestionAdminResult.Purged purged = ingestionAdminFacade.purgeSucceeded(LocalDateTime.now());
			if (purged.deleted() > 0) {
				log.info("아웃박스 주간 정리: SUCCEEDED {}건 삭제(기준선 {} 이전)", purged.deleted(), purged.cutoff());
			}
		} catch (RuntimeException e) {
			log.warn("아웃박스 주간 정리 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}
}
