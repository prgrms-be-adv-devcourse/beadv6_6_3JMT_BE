package com.prompthub.admin.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;

class GlobalExceptionHandlerTest {

	@Test
	@DisplayName("GlobalExceptionHandler는 어떤 도메인 패키지의 예외 타입도 참조하지 않는다")
	void handlesNoDomainSpecificExceptionType() {
		List<Class<?>> referencedTypes = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
			.flatMap(method -> Stream.concat(
				Arrays.stream(method.getAnnotation(ExceptionHandler.class).value()),
				Arrays.stream(method.getParameterTypes())))
			.distinct()
			.toList();

		assertThat(referencedTypes)
			.noneMatch(type -> isDomainPackage(type.getPackageName()));
	}

	private boolean isDomainPackage(String packageName) {
		return packageName.startsWith("com.prompthub.admin.")
			&& !packageName.startsWith("com.prompthub.admin.global");
	}
}
