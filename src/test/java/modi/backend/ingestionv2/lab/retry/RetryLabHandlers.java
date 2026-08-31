package modi.backend.ingestionv2.lab.retry;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.EventDispatcher;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.ingestionv2.common.queue.RedisStreamDispatcher;

/**
 * 재시도 실험 전용 배선 - 소비자와 발행 어댑터.
 *
 * <ul>
 *   <li>도메인 핸들러 빈을 전부 걷어낸다 - {@code IngestionEventRouter.route} 가 {@code findFirst} 라
 *       중복 등록 상태에서는 어느 핸들러가 잡힐지 비결정적이다(계획서 공통 조건)</li>
 *   <li>걷어내는 기준은 포트 타입 하나 - 도메인 클래스 이름을 알지 못한다</li>
 *   <li>남기는 빈 이름은 {@code lab} 접두사 - 공용 계층 테스트의 {@code recording} 접두사와 겹치지 않게</li>
 *   <li>주 실험 이벤트는 {@code DETAIL_READY} - 스트림 배정이 있는 타입이라야 PEL 에 들어간다(F-12)</li>
 *   <li>발행 어댑터는 실물을 감싼 토글 - 장애를 켠 구간에서만 예외를 던지고 평소엔 그대로 위임한다</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
class RetryLabHandlers {

	@Bean
	static BeanFactoryPostProcessor removeDomainHandlersForRetryLab() {
		return beanFactory -> {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			for (String name : beanFactory.getBeanNamesForType(IngestionEventHandler.class, true, false)) {
				if (!name.startsWith("lab")) {
					registry.removeBeanDefinition(name);
				}
			}
		};
	}

	@Bean
	FaultInjectingEventHandler labDetailReadyHandler() {
		return new FaultInjectingEventHandler(IngestionEventType.DETAIL_READY);
	}

	@Bean
	@Primary
	EventDispatcher labEventDispatcher(RedisStreamDispatcher redisStreamDispatcher) {
		return new ToggleableEventDispatcher(redisStreamDispatcher);
	}
}
