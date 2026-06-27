package com.prompthub.order.global.web;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class OrderServiceAuthInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (HttpMethod.OPTIONS.matches(request.getMethod())) {
			return true;
		}

		String userId = request.getHeader(AuthHeaders.USER_ID);
		String userRole = request.getHeader(AuthHeaders.USER_ROLE);

		if (isBlank(userId) || isBlank(userRole)) {
			throw new OrderException(ErrorCode.INVALID_AUTHENTICATION);
		}
		if (!AuthHeaderRoles.hasRole(userRole, AuthHeaders.USER) && !AuthHeaderRoles.hasRole(userRole, "BUYER")) {
			throw new OrderException(ErrorCode.FORBIDDEN);
		}

		return true;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
