package modi.backend.ingestion.interfaces;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.ingestion.application.ExhibitionIngestionOrchestrator;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.support.error.CoreException;

/**
 * ExhibitionSyncScheduler 단위 검증. 매일 자정 트리거 시 동기화(목록 수집·스테이징) → 장르 소비(신규분)
 * → 영업시간 보강을 순서대로 호출하고, 실패(외부 API 불가 등)해도 예외를 삼켜 스케줄러 스레드가 죽지
 * 않아야 한다(다음 주기 재시도). 상세·AI는 이벤트 소비로 릴레이가 처리하므로 여기선 진입 트리거만 본다.
 */
class ExhibitionSyncSchedulerTest {

	private ExhibitionIngestionOrchestrator ingestionOrchestrator;
	private ExhibitionSyncScheduler scheduler;

	@BeforeEach
	void setUp() {
		ingestionOrchestrator = mock(ExhibitionIngestionOrchestrator.class);
		scheduler = new ExhibitionSyncScheduler(ingestionOrchestrator);
	}

	@Test
	@DisplayName("syncDaily: 동기화(SCHEDULE 트리거) → 영업시간 보강만 호출한다(후속 스텝은 릴레이가 소비)")
	void syncDaily_동기화후_영업시간_순서호출() {
		scheduler.syncDaily();

		// 스케줄러가 아는 건 syncCatalog 하나뿐이다 — 무엇을 발견하고 큐에 싣는지는 그 안이고,
		// 실제 조회(상세·장르·승격·영업시간)는 이벤트를 릴레이가 소비한다.
		verify(ingestionOrchestrator, times(1)).syncCatalog(SyncTrigger.SCHEDULE);
		verifyNoMoreInteractions(ingestionOrchestrator);
	}

	@Test
	@DisplayName("syncDaily: facade가 예외를 던져도 삼켜서 다음 주기까지 살아있는다(영업시간 보강은 계속)")
	void syncDaily_예외삼킴() {
		willThrow(new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 호출 실패"))
				.given(ingestionOrchestrator).syncCatalog(SyncTrigger.SCHEDULE);

		assertThatCode(() -> scheduler.syncDaily()).doesNotThrowAnyException();

		verify(ingestionOrchestrator, times(1)).syncCatalog(SyncTrigger.SCHEDULE);
	}
}
