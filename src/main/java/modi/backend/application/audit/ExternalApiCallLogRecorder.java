package modi.backend.application.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiCallLogRepository;

/**
 * 외부 호출 감사(append-only) 기록 협력 객체 — 각 서비스가 외부 호출 <b>직후, 반영 트랜잭션 시작 전</b>에
 * 명시적으로 부른다. AOP가 아니라 명시 호출인 이유: {@code requestKey}는 API마다 어휘가 다르고
 * (문화포털=external_id, 구글=place_key, 목록=page, AI만 model 기록), {@code outcome}은 응답을 해석할 줄 아는
 * 서비스만 판정할 수 있다(HTTP 200 + 빈 결과 = NO_DATA). 이 로그는 인프라 이벤트가 아니라
 * "도메인이 이 호출을 어떻게 해석했는가"의 기록이다.
 *
 * <p><b>감사는 호출자의 트랜잭션과 생사를 같이하면 안 된다</b> — 두 방향 모두 끊는다:
 * <ul>
 *   <li><b>사실 보존({@code REQUIRES_NEW})</b> — 호출은 이미 일어났고 과금도 이미 됐다. 그 뒤 비즈니스
 *       트랜잭션이 롤백된다고 "구글을 부른 적 없음"이 되면 감사가 거짓말을 한다. 우리가 태운 돈은 롤백되지 않는다.</li>
 *   <li><b>오염 차단(try/catch 삼킴)</b> — {@code REQUIRES_NEW}는 커밋/롤백 경계만 분리할 뿐, 여기서 던진
 *       예외는 그대로 호출한 서비스로 전파되어 비즈니스 흐름을 깬다. 부가 기록이 본 기능을 멈추지 않도록
 *       저장 실패는 삼키고 warn만 남긴다.</li>
 * </ul>
 * 호출 위치 규칙(외부 호출 직후·반영 tx 전)이면 열려 있는 트랜잭션이 없어 REQUIRES_NEW가 커넥션을 이중
 * 점유하지 않고, 실패한 호출(429 추이·폴백 원인)도 반영 트랜잭션과 무관하게 남는다.
 *
 * <p>mock·미호출 경로는 기록하지 않는다 — 부르지 않은 호출을 남기면 "구글을 이만큼 불렀다"는 거짓이 쌓인다
 * (기록 여부 게이트는 순회·mock 여부를 아는 호출부의 몫이다).
 */
@Component
@RequiredArgsConstructor
public class ExternalApiCallLogRecorder {

	private static final Logger log = LoggerFactory.getLogger(ExternalApiCallLogRecorder.class);

	private final ExternalApiCallLogRepository externalApiCallLogRepository;

	/** 로그는 항상 커밋 — 그리고 로그 실패가 비즈니스 실패가 되지 않도록 삼킨다(warn만). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(ExternalApiCallLog callLog) {
		try {
			externalApiCallLogRepository.save(callLog);
		} catch (RuntimeException e) {
			log.warn("외부 호출 감사 기록 실패(무시): {}", e.getMessage());
		}
	}
}
