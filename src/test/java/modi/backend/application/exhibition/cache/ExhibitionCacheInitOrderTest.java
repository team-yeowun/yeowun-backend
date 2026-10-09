package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * - 캐시 선언 클래스는 어느 쪽이 먼저 초기화돼도 깨지지 않아야 함
 *   - 중첩 선언이 바깥 상수를 읽던 때, 중첩 쪽이 먼저 초기화되면 바깥의 목록 상수가 null을 담다가 NPE가 났다
 *   - 테스트 실행 순서에 따라서만 드러나는 종류라, 새 클래스로더로 "중첩 선언 먼저"를 강제해 고정한다
 */
class ExhibitionCacheInitOrderTest {

	@Test
	@DisplayName("중첩 선언을 먼저 초기화해도 바깥 목록에 null이 담기지 않는다")
	void 중첩선언_먼저_초기화() throws Exception {
		URL classes = ExhibitionCache.class.getProtectionDomain().getCodeSource().getLocation();
		try (URLClassLoader fresh = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				// 우리 코드는 새 로더에서, 나머지(라이브러리)는 테스트 로더에서 읽는다
				if (name.startsWith("modi.backend.")) {
					synchronized (getClassLoadingLock(name)) {
						Class<?> loaded = findLoadedClass(name);
						return loaded != null ? loaded : findClass(name);
					}
				}
				return ExhibitionCacheInitOrderTest.class.getClassLoader().loadClass(name);
			}
		}) {
			Class<?> nested = Class.forName(ExhibitionCache.ExploreLatestP1.class.getName(), true, fresh);
			Object instance = nested.getField("INSTANCE").get(null);
			Class<?> outer = Class.forName(ExhibitionCache.class.getName(), true, fresh);
			List<Object> lists = List.copyOf((List<?>) outer.getField("LISTS").get(null));
			List<Object> all = List.copyOf((List<?>) outer.getField("ALL").get(null));

			assertThat(instance).isNotNull();
			assertThat(lists).hasSize(7).doesNotContainNull().contains(instance);
			assertThat(all).hasSize(8).doesNotContainNull();
		}
	}
}
