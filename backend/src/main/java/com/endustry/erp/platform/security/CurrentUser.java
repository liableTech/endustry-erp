package com.endustry.erp.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Who is making the current request. */
public final class CurrentUser {

	private static final String ROLE_PREFIX = "ROLE_";

	private CurrentUser() {
	}

	public static String email() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth == null || !auth.isAuthenticated() ? "system" : auth.getName();
	}

	/** The product admin behind a support session, or null for an ordinary sign-in. */
	public static String impersonator() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString(TokenService.CLAIM_IMPERSONATOR) : null;
	}

	public static boolean isImpersonated() {
		return impersonator() != null;
	}

	/** The name written to the audit trail; a support session records both people. */
	public static String actor() {
		String impersonator = impersonator();
		return impersonator == null ? email() : email() + " (support: " + impersonator + ")";
	}

	/** The caller's role, or null when the request is anonymous. */
	public static Role role() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null) {
			return null;
		}
		return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
				.filter(a -> a.startsWith(ROLE_PREFIX)).map(a -> a.substring(ROLE_PREFIX.length()))
				.filter(CurrentUser::isRole).map(Role::valueOf).findFirst().orElse(null);
	}

	public static boolean isOwner() {
		return role() == Role.OWNER;
	}

	private static boolean isRole(String name) {
		try {
			Role.valueOf(name);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
