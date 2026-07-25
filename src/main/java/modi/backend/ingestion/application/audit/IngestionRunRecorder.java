package modi.backend.ingestion.application.audit;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.domain.audit.IngestionRun;
import modi.backend.ingestion.domain.audit.IngestionRunRepository;

/**
 * 수집 런 감사(append-only) 기록 컴포넌트 — 동기화 루프({@code ExhibitionIngestionOrchestrator})가 실행 종료 시
 * run 하나만 넘겨 위임한다(집계는 run이 이미 누적해 갖고 있다). 동기화 요약 로그도 여기 소유다.
 *
 * <p>best-effort 삼킴이다: 부가 기록이라 실패해도 동기화 결과를 깨지 않는다. 콜 감사
 * ({@code ExternalApiCallLogRecorder})와 달리 {@code REQUIRES_NEW}가 아니다 — 이 기록은 루프 종료 후
 * 트랜잭션 밖에서 한 번 불리므로 분리할 트랜잭션 자체가 없다.
 */
@Component
@RequiredArgsConstructor
public class IngestionRunRecorder {

	private static final Logger log = LoggerFactory.getLogger(IngestionRunRecorder.class);

	private final IngestionRunRepository ingestionRunRepository;

	/** 런 마감(종료 시각) + 요약 로그 + 감사 저장 — 저장 실패는 삼킨다(동기화 결과를 깨지 않게). */
	public void record(IngestionRun run) {
		if (run.hasActivity()) {
			log.info("전시 동기화: 수집 {} / 신규 스테이징 {}", run.getCollected(), run.getInserted());
		}
		try {
			run.finished(LocalDateTime.now());
			ingestionRunRepository.save(run);
		} catch (RuntimeException e) {
			log.warn("동기화 실행 기록 실패(동기화는 계속): {}", e.getMessage());
		}
	}
}
