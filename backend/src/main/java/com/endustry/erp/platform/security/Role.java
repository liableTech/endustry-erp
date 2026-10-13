package com.endustry.erp.platform.security;

/**
 * Preset roles from BRD section 7.2. What each may do is data, held in {@link RolePermission}.
 * PLATFORM_ADMIN is different in kind: it belongs to the product owner, not to any client.
 */
public enum Role {
	OWNER, ACCOUNTS, SALES, PURCHASE, STORE, PRODUCTION, SUPERVISOR, QUALITY, CA, PLATFORM_ADMIN;

	/** The roles a client can give its own users. */
	public static Role[] clientRoles() {
		return java.util.Arrays.stream(values()).filter(r -> r != PLATFORM_ADMIN).toArray(Role[]::new);
	}
}
