package modi.backend.ingestion.interfaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.ExhibitionIngestionOrchestrator;
import modi.backend.ingestion.domain.SyncTrigger;

/**
 * 공공데이터 전시 카탈로그 <b>정기</b> 동기화 스케줄러 — 진입 트리거일 뿐, 흐름은 전부
 * {@link ExhibitionIngestionOrchestrator}가 조율한다. 신규 전시 포착의 <b>주 경로</b>다.
 *
 * <p>매일 자정 {@code syncCatalog} <b>하나만</b> 부른다 — 이 클래스는 트리거일 뿐이라 "이번 회차에 무엇을 하는가"를
 * 알지 못한다. 목록 수집·스테이징도, 영업시간 확인 대상 스윕도 그 메서드 안이고, 실제 외부 조회(상세·장르·승격·
 * 영업시간)는 거기서 발행된 이벤트를 릴레이가 드레인한다.
 *
 * <p><b>실 카탈로그 수집의 유일한 트리거</b>다 — 부팅 시 1회 동기화(구 {@code ExhibitionCatalogBootSync})는
 * 삭제됐다: 초기 적재는 시드 SQL({@code LocalExhibitionSeeder} 계열)로 하기로
 * 정해졌고, 남겨두면 재기동마다 목록 호출이 재발할 뿐이었다(사용자 결정 — 재도입 ❌).
 * 인증키 미설정이면 syncCatalog 내부에서 스킵되어 외부 호출 없이 끝난다.
 */
@Component
@ConditionalOnProperty(name = "app.exhibition.enrich.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExhibitionSyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionSyncScheduler.class);

	private final ExhibitionIngestionOrchestrator ingestionOrchestrator;

	/** 로컬 시드 모드면 정기 동기화도 건너뛴다(로컬 실 API 호출 0 — 시드 데이터 유지). */
	@Value("${app.local-seed.enabled:false}")
	private boolean localSeedEnabled;

	/** 매일 자정: 목록 수집·스테이징 → 신규분 장르 드레인 → 신규/만료 장소 영업시간 보강. 실패해도 다음 주기에 재시도. */
	@Scheduled(cron = "${app.exhibition.sync.cron:0 0 0 * * *}")
	public void syncDaily() {
		if (localSeedEnabled) {
			log.info("전시 정기 동기화 skip — app.local-seed.enabled=true (로컬 시드 데이터 유지, 외부 API 호출 안 함)");
			return;
		}
		try {
			// 이 스케줄러가 아는 건 "동기화를 트리거한다"뿐이다 — 이번 회차에 무엇을 발견하고 무엇을 큐에 싣는지는
			// 전부 syncCatalog 안이고, 실제 조회(상세·장르·승격·영업시간)는 이벤트를 릴레이가 드레인한다.
			// 결과 요약 로그는 런 감사(IngestionRunRecorder)가 남긴다.
			ingestionOrchestrator.syncCatalog(SyncTrigger.SCHEDULE);
		} catch (RuntimeException e) {
			log.warn("전시 정기 동기화 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}
}
