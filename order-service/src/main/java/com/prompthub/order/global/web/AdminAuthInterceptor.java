package com.prompthub.order.global.web;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (HttpMethod.OPTIONS.matches(request.getMethod())) {
			return true;
		}

		String userRole = request.getHeader(AuthHeaders.USER_ROLE);

		if (userRole == null || userRole.isBlank()) {
			throw new OrderException(ErrorCode.INVALID_AUTHENTICATION);
		}
		if (!AuthHeaderRoles.hasRole(userRole, AuthHeaders.ADMIN)) {
			throw new OrderException(ErrorCode.FORBIDDEN);
		}

		return true;
	}
}
