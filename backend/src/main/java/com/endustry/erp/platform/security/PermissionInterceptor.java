package com.endustry.erp.platform.security;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.endustry.erp.platform.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Enforces access for every /api/{module}/... call: reading needs VIEW, anything that changes
 * data needs FULL. New endpoints are covered automatically by living under a module path.
 *
 * Product admins and client users are kept apart: the product area is closed to client users,
 * and client data is closed to product admins unless they start a support session as a client user.
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

	private static final Set<String> OPEN_AREAS = Set.of("auth", "public");
	private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final PermissionService permissions;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String[] segments = request.getRequestURI().split("/");
		if (segments.length < 3 || !"api".equals(segments[1]) || OPEN_AREAS.contains(segments[2])) {
			return true;
		}
		String module = segments[2];
		Role role = CurrentUser.role();
		boolean reading = READ_METHODS.contains(request.getMethod());

		if (PermissionService.PLATFORM_MODULE.equals(module)) {
			if (role != Role.PLATFORM_ADMIN) {
				throw ApiException.forbidden("FORBIDDEN", "Only a product admin can open the product admin area");
			}
			return true;
		}
		if (role == Role.PLATFORM_ADMIN) {
			throw ApiException.forbidden("PLATFORM_ONLY", "Product admins do not open client data directly. Sign in as a client user from the Clients page.");
		}
		if (!permissions.moduleEnabled(module)) {
			throw ApiException.forbidden("MODULE_DISABLED", "The " + module + " module is not part of your subscription");
		}
		AccessLevel required = reading ? AccessLevel.VIEW : AccessLevel.FULL;
		if (!permissions.level(role, module).allows(required)) {
			throw ApiException.forbidden("FORBIDDEN", "Your role does not have "
					+ (reading ? "access to" : "permission to change") + " " + module);
		}
		// A support session can look at and fix business data, but never touch who can sign in.
		if (!reading && CurrentUser.isImpersonated() && "admin".equals(module)) {
			throw ApiException.forbidden("IMPERSONATION_RESTRICTED", "Users and access rights cannot be changed in a support session");
		}
		return true;
	}
}
