package modi.backend.interfaces.exhibition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.ExhibitionFacade;

/**
 * 목록 캐시 워밍 트리거 — <b>6시간마다</b>({@code 03 / 09 / 15 / 21시}) 목록 7종을 새 값으로 반영한다.
 *
 * <p><b>왜 조회수 반영 30분 뒤인가</b>: {@link ExhibitionViewCountFlushScheduler}가 02:30에 누산분을
 * 정본으로 옮긴다. 인기순 목록과 배너가 조회수로 정렬되므로, 워밍이 그보다 먼저 돌면
 * <b>한 창 전의 조회수로 만든 목록</b>이 6시간 동안 서빙된다. 순서가 이 설계의 핵심이다.
 *
 * <p>수집 배치(01시)보다도 뒤라, 그날 새로 들어온 전시가 첫 워밍부터 반영된다.
 * 워밍이 실패해도 사고는 아니다 — L2 TTL이 7시간이라 다음 주기까지는 옛 값이 서빙되고,
 * 두 번 연속 실패하면 만료돼 Lazy Loading이 받는다.
 *
 * <p><b>인스턴스 2대 주의</b>: 분산 락이 없어 두 서버가 각자 한 번씩 워밍한다. 워밍은 <b>덮어쓰기라 멱등</b>
 * 이므로 정합성 문제는 없고 조회 쿼리만 2배로 나간다(7쿼리 × 2). 조회수 반영과 헷갈리지 말 것 —
 * 그쪽은 <b>누적 연산</b>이라 두 번 돌면 조회수가 두 배가 되고, 그래서 거기에는 원자적 수거가 들어가 있다.
 */
@Component
@RequiredArgsConstructor
public class ExhibitionCacheWarmScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionCacheWarmScheduler.class);

	private final ExhibitionFacade exhibitionFacade;

	@Value("${app.local-seed.enabled:false}")
	private boolean localSeedEnabled;

	@Scheduled(cron = "${app.exhibition.cache.warm-cron:0 0 3,9,15,21 * * *}")
	public void warmPeriodically() {
		if (localSeedEnabled) {
			return;
		}
		log.info("전시 목록 캐시 워밍 시작");
		exhibitionFacade.warmListCaches();
	}
}
