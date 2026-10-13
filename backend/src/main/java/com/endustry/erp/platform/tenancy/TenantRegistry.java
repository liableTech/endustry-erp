package com.endustry.erp.platform.tenancy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.endustry.erp.platform.common.ApiException;

/**
 * Looks up clients by their slug, cached because every request checks its client's status and
 * switched-on features. Also runs work on behalf of a particular client.
 */
@Component
public class TenantRegistry {

	public interface TenantRepository extends JpaRepository<Tenant, Long> {
		Optional<Tenant> findBySlug(String slug);

		boolean existsBySlug(String slug);
	}

	private final TenantRepository tenants;
	private final TransactionTemplate joinOrStart;
	private final TransactionTemplate alwaysNew;
	private final Map<String, Optional<Tenant>> cache = new ConcurrentHashMap<>();

	public TenantRegistry(TenantRepository tenants, PlatformTransactionManager transactions) {
		this.tenants = tenants;
		this.joinOrStart = new TransactionTemplate(transactions);
		this.alwaysNew = new TransactionTemplate(transactions);
		this.alwaysNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public Optional<Tenant> find(String slug) {
		return cache.computeIfAbsent(slug, tenants::findBySlug);
	}

	/** The client the current request belongs to; empty for product admins and anonymous requests. */
	public Optional<Tenant> current() {
		String slug = TenantContext.get();
		return TenantContext.PLATFORM.equals(slug) || TenantContext.NONE.equals(slug) ? Optional.empty() : find(slug);
	}

	public Tenant require(Long id) {
		return tenants.findById(id).orElseThrow(() -> ApiException.notFound("Client", id));
	}

	public void forget(String slug) {
		cache.remove(slug);
	}

	/**
	 * Runs {@code work} as the given client, in its own transaction. A database session belongs to
	 * one client for its whole life, so the surrounding session is set aside and a fresh one is
	 * opened for the duration; anything returned is detached from it.
	 */
	public <T> T runAs(String slug, Supplier<T> work) {
		String previous = TenantContext.get();
		TenantContext.set(slug);
		try {
			// The outer template guarantees there is a transaction to suspend, which is what makes
			// the inner one open a new session instead of reusing the request's.
			return joinOrStart.execute(outer -> alwaysNew.execute(inner -> work.get()));
		} finally {
			TenantContext.set(previous);
		}
	}
}
