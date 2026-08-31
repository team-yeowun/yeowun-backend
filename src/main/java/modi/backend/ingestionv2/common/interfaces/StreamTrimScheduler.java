package modi.backend.ingestionv2.common.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.lock.IngestionJobLock;
import modi.backend.ingestionv2.common.queue.StreamTrimmer;

/**
 * 스트림 길이 관리 - 없으면 메모리가 계속 차오른다.
 *
 * <ul>
 *   <li>네 스트림을 통째로 자르는 잡이라 인스턴스 한 대만 돈다 - XTRIM 은 멱등이지만
 *       두 대가 같은 시각에 같은 스트림을 자를 이유가 없다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class StreamTrimScheduler {

	private static final String TRIM_JOB = "stream-trim";

	private final StreamTrimmer streamTrimmer;
	private final IngestionJobLock jobLock;

	@Scheduled(cron = "${app.ingestion.v2.trim-cron:0 10 * * * *}")
	public void trim() {
		jobLock.runIfAcquired(TRIM_JOB, streamTrimmer::trimAll);
	}
}
