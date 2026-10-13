package com.endustry.erp.platform.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Which client the current thread is working for. Every table carries a tenant column, and
 * Hibernate filters and stamps rows with the value held here, so one deployment serves many
 * clients without any query having to remember to filter.
 */
public final class TenantContext {

	/** The product owner's own space: product admins and the default branding live here. */
	public static final String PLATFORM = "platform";

	/** No client is known, e.g. an anonymous request. Nothing is stored under this value. */
	public static final String NONE = "none";

	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static String get() {
		String tenant = CURRENT.get();
		return tenant == null ? NONE : tenant;
	}

	public static void set(String tenant) {
		CURRENT.set(tenant);
	}

	public static void clear() {
		CURRENT.remove();
	}

	public static boolean isPlatform() {
		return PLATFORM.equals(get());
	}

	/** Tells Hibernate which client each new database session belongs to. */
	@Component
	static class Resolver implements CurrentTenantIdentifierResolver<String> {

		@Override
		public String resolveCurrentTenantIdentifier() {
			return TenantContext.get();
		}

		@Override
		public boolean validateExistingCurrentSessions() {
			return false;
		}
	}
}
