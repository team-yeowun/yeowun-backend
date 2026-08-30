package modi.backend.ingestionv2.common.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.queue.StreamTrimmer;

/** 스트림 길이 관리 - 없으면 메모리가 계속 차오른다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class StreamTrimScheduler {

	private final StreamTrimmer streamTrimmer;

	@Scheduled(cron = "${app.ingestion.v2.trim-cron:0 10 * * * *}")
	public void trim() {
		streamTrimmer.trimAll();
	}
}
