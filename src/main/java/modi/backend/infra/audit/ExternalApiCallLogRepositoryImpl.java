package modi.backend.infra.audit;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiCallLogRepository;

/**
 * 외부 호출 감사 어댑터 — 평범한 저장 위임이다.
 *
 * <p>트랜잭션 분리({@code REQUIRES_NEW})와 예외 삼킴은 여기가 아니라
 * {@link modi.backend.application.audit.ExternalApiCallLogRecorder}의 몫이다 —
 * "감사가 호출자 트랜잭션과 생사를 같이하면 안 된다"는 규칙은 저장 기술이 아니라 유스케이스의 결정이라
 * application에 있다. 어댑터는 영속화만 안다.
 */
@Repository
@RequiredArgsConstructor
public class ExternalApiCallLogRepositoryImpl implements ExternalApiCallLogRepository {

	private final ExternalApiCallLogJpaRepository jpaRepository;

	@Override
	public ExternalApiCallLog save(ExternalApiCallLog callLog) {
		return jpaRepository.save(callLog);
	}
}
