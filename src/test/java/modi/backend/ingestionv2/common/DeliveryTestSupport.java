package modi.backend.ingestionv2.common;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import modi.backend.ingestionv2.IngestionTestSupport;

/**
 * 공용 계층 테스트의 토대. 도메인 핸들러를 걷어내고 기록용 핸들러만 남긴다.
 *
 * <p>이 폴더의 테스트는 도메인 클래스를 한 번도 참조하지 않는다 - 배달 계층은 어떤 핸들러와도 동작한다는
 * 주장을 테스트 배선 자체가 증명한다.
 */
@Import(RecordingHandlers.class)
abstract class DeliveryTestSupport extends IngestionTestSupport {

	@Autowired protected RecordingEventHandler recordingCollectedHandler;
	@Autowired protected RecordingEventHandler recordingEnrichedHandler;
	@Autowired protected RecordingEventHandler recordingStagedHandler;

	@BeforeEach
	void resetRecordingHandlers() {
		recordingCollectedHandler.reset();
		recordingEnrichedHandler.reset();
		recordingStagedHandler.reset();
	}
}
