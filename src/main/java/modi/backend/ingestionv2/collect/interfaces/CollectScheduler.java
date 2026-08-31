package modi.backend.ingestionv2.collect.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 수집 트리거.
 *
 * <ul>
 *   <li>새벽 1시와 12시간 뒤인 13시에 기상. 회차 키는 날짜 하나라 두 번째 기상은 선점에 실패하고 즉시 종료</li>
 *   <li>헛기상이 아니라 안전장치 - 1시에 인스턴스가 재시작 중이라 못 깨어난 날을 13시가 만회한다</li>
 *   <li>주기가 아니라 시각으로 둔 이유 - fixedDelay 는 기동 시점에 따라 기상 시각이 배포마다 달라진다</li>
 *   <li>인스턴스가 두 대여도 회차 선점이 단일 실행을 만듦 (분산 락 불필요)</li>
 *   <li>파사드만 호출 (무엇을 어떤 순서로 하는지는 격벽 안이 안다)</li>
 *   <li>비동기 배달 스위치에 함께 걸림 - 테스트가 회차를 직접 밀 수 있게 함</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class CollectScheduler {

	private final CollectFacade collectFacade;

	@Scheduled(cron = "${app.ingestion.v2.collect-cron:0 0 1,13 * * *}")
	public void collect() {
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(IngestionClock.today()));
		log.info(
				"수집 회차 종료 claimed={} collected={} skipped={} failed={}",
				result.claimed(), result.collected(), result.skipped(), result.failed());
	}
}
