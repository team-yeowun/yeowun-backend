package modi.backend.interfaces.exhibition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.ExhibitionFacade;

/**
 * 조회수 반영 트리거 — 6시간마다({@code 02:30 / 08:30 / 14:30 / 20:30}) 누산분을 정본으로 옮긴다.
 *
 * <p><b>왜 6시간인가</b>: 운영 Redis는 비영속이라 재시작하면 누산분이 사라진다. 주기가 짧을수록 잃는 창이 짧고,
 * 길수록 인기순 정렬축이 오래 고정돼 키셋 페이징이 안정적이다. 그 사이에서 고른 값이다.
 *
 * <p><b>02:30인 이유</b>: 수집 배치가 01시, 목록 캐시 워밍이 그 뒤라 <b>워밍이 최신 조회수를 읽도록</b>
 * 그 앞에 둔다. 순서가 뒤집히면 인기순 목록이 한 창 전의 조회수로 만들어진다.
 *
 * <p>인스턴스가 둘이어도 안전하다 — 수거가 원자적이라 한쪽만 가져간다(중복 실행 = 조회수 두 배가 아니다).
 */
@Component
@RequiredArgsConstructor
public class ExhibitionViewCountFlushScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionViewCountFlushScheduler.class);

	private final ExhibitionFacade exhibitionFacade;

	@Scheduled(cron = "${app.exhibition.view-count.flush-cron:0 30 2,8,14,20 * * *}")
	public void flush() {
		try {
			exhibitionFacade.flushViewCounts();
		} catch (RuntimeException e) {
			// 수거분은 누산기로 되돌아가 있다 — 다음 주기가 다시 가져간다.
			log.warn("조회수 반영 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}
}
