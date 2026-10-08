package modi.backend.interfaces.exhibition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.ExhibitionFacade;

/**
 * - 랭킹 재계산 트리거: 5분마다 + 기동 직후 1회
 *   - 순위판을 날짜 버킷(원본)에서 다시 만들고 홈 배너 캐시를 지움(다음 조회가 새 순위로 적재)
 *   - 기동 직후 1회는 배포 직후·Redis 교체 직후 첫 5분 동안 배너가 비지 않게 하려는 것
 *
 * - 두 앱이 각자 실행하고 분산 락을 걸지 않음
 *   - 같은 원본에서 다시 만들어 덮어써 몇 번 돌아도 결과가 같음 (조회수 반영처럼 누적 연산이 아님)
 *   - 대가는 5분마다 후보 조회 1번 · Lua 1번이 2배로 나가는 것뿐
 */
@Component
@RequiredArgsConstructor
public class ExhibitionRankingScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionRankingScheduler.class);

	private final ExhibitionFacade exhibitionFacade;

	@EventListener(ApplicationReadyEvent.class)
	public void rebuildOnStartup() {
		rebuild();
	}

	@Scheduled(cron = "${app.exhibition.ranking.rebuild-cron:0 */5 * * * *}")
	public void rebuild() {
		try {
			exhibitionFacade.rebuildRankings();
		} catch (RuntimeException e) {
			// 순위판은 직전 값으로 계속 서빙된다 — 다음 주기가 다시 만든다.
			log.warn("랭킹 재계산 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}
}
