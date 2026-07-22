package modi.backend.ingestion.infra.failover;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * 장르 분류 <b>폴백 체인</b> — 1차(Gemini)가 실패하면 2차(Claude)로 전환한다(ADR-11).
 *
 * <p>각 공급자는 <b>한 번씩만</b> 부른다. 호출 내 즉시 재시도·서킷브레이커(resilience4j)는 <b>일단 걷어냈다</b> —
 * 얻는 것에 비해 조립과 읽기 비용이 컸다(사용자 결정). 필요해지면 다시 넣는다.
 * 실패를 이어받는 것은 아웃박스다: 두 공급자가 모두 실패하면 {@link GenreClassificationException}을 던지고,
 * 아웃박스 메시지가 RETRYABLE로 남아 draft는 분류될 때까지 승격을 대기한다(재시작을 넘는 durable 재시도 — ADR-10).
 *
 * <p>빈이 아니라 {@code GenreConfig}가 조립하는 일반 클래스다 — 어떤 공급자를 어떤 순서로 묶을지는 구성의 결정이다.
 */
public class FailoverGenreClassifier implements GenreClassifier {

	private static final Logger log = LoggerFactory.getLogger(FailoverGenreClassifier.class);

	private final GenreClassifier primary;
	private final GenreClassifier secondary;

	public FailoverGenreClassifier(GenreClassifier primary, GenreClassifier secondary) {
		this.primary = primary;
		this.secondary = secondary;
	}

	@Override
	public GenreResult classify(GenreClassification input) {
		return callWithFailover(() -> primary.classify(input), () -> secondary.classify(input));
	}

	/** 1차 → 실패 시 2차 → 둘 다 실패면 분류 실패 예외(아웃박스가 durable 재시도를 잇는다). */
	private <T> T callWithFailover(Supplier<T> primaryCall, Supplier<T> secondaryCall) {
		try {
			return primaryCall.get();
		} catch (Exception primaryFailure) {
			log.warn("장르 1차 공급자 실패 — 2차로 전환: {}", primaryFailure.getMessage());
			try {
				return secondaryCall.get();
			} catch (Exception secondaryFailure) {
				GenreClassificationException failure = new GenreClassificationException(
						"장르 분류 전 공급자 실패(1차: " + primaryFailure.getMessage()
								+ " / 2차: " + secondaryFailure.getMessage() + ")", secondaryFailure);
				failure.addSuppressed(primaryFailure);
				throw failure;
			}
		}
	}
}
