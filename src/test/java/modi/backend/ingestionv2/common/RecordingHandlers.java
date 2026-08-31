package modi.backend.ingestionv2.common;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;

/**
 * 공용 계층 테스트 전용 배선.
 *
 * <ul>
 *   <li>도메인 핸들러 빈을 전부 걷어낸다 - 배달 계층이 어떤 핸들러와도 동작한다는 것이 이 폴더의 주장</li>
 *   <li>걷어내는 기준은 포트 타입 하나 - 도메인 클래스 이름을 알지 못한다</li>
 *   <li>네 도메인 폴더는 이 설정을 가져오지 않는다 - 그쪽은 자기 실물 핸들러가 돌아야 한다</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingHandlers {

	@Bean
	static BeanFactoryPostProcessor removeDomainHandlers() {
		return beanFactory -> {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			for (String name : beanFactory.getBeanNamesForType(IngestionEventHandler.class, true, false)) {
				if (!name.startsWith("recording")) {
					registry.removeBeanDefinition(name);
				}
			}
		};
	}

	@Bean
	RecordingEventHandler recordingCollectedHandler() {
		return new RecordingEventHandler(IngestionEventType.COLLECTED);
	}

	@Bean
	RecordingEventHandler recordingEnrichedHandler() {
		return new RecordingEventHandler(IngestionEventType.ENRICHED);
	}

	@Bean
	RecordingEventHandler recordingStagedHandler() {
		return new RecordingEventHandler(IngestionEventType.STAGED);
	}
}
