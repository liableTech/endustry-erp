package com.endustry.erp.platform.tenancy;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.CurrentUser;
import com.endustry.erp.platform.security.Role;
import com.endustry.erp.platform.security.TokenService;
import com.endustry.erp.platform.settings.CompanyProfile;
import com.endustry.erp.platform.tenancy.TenantProvisioner.UserSpec;
import com.endustry.erp.platform.tenancy.TenantRegistry.TenantRepository;
import com.endustry.erp.platform.validation.Gstin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * What a product admin can do: bring a client on, switch features on and off for it, suspend it,
 * and step into a client user's shoes to help them. Looking across clients is done with plain SQL
 * on purpose; everything else in the application is confined to one client by Hibernate.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PlatformService {

	private static final Set<String> RESERVED_SLUGS = Set.of(TenantContext.PLATFORM, TenantContext.NONE, "admin", "api", "www", "demo-client");
	private static final Duration SUPPORT_SESSION = Duration.ofMinutes(30);
	private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
	private static final int GENERATED_PASSWORD_LENGTH = 14;
	private static final String ENTITY = "Client";

	private final TenantRepository tenants;
	private final TenantRegistry registry;
	private final TenantProvisioner provisioner;
	private final TokenService tokens;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;
	private final SecureRandom random = new SecureRandom();

	@PersistenceContext
	private EntityManager em;

	// ---- requests and views ----

	public record CreateClient(
			@NotBlank(message = "is required") @Size(max = 120) String name,
			@NotBlank(message = "is required") @Pattern(regexp = "[a-z][a-z0-9-]{2,29}", message = "must be 3 to 30 lowercase letters, digits or dashes, starting with a letter") String slug,
			@NotNull(message = "is required") Industry industry,
			@NotBlank(message = "is required") @Pattern(regexp = "\\d{2}", message = "must be a 2-digit GST state code") String stateCode,
			@Gstin String gstin,
			@NotBlank(message = "is required") @Size(max = 100) String ownerName,
			@NotBlank(message = "is required") @Email(message = "is not a valid email address") String ownerEmail,
			@Size(min = 8, max = 72, message = "must be 8 to 72 characters") String ownerPassword,
			Boolean whiteLabel,
			Tenant.Hosting hosting,
			@Min(value = 0, message = "cannot be negative") @Max(value = 10000, message = "is too large") Integer maxUsers,
			Set<String> modules,
			@Size(max = 400) String notes) {
	}

	public record UpdateClient(
			@NotBlank(message = "is required") @Size(max = 120) String name,
			@NotNull(message = "is required") Industry industry,
			@NotNull(message = "is required") Tenant.Status status,
			@NotNull(message = "is required") Tenant.Hosting hosting,
			@NotNull(message = "is required") Boolean whiteLabel,
			@NotNull(message = "is required") @Min(value = 0, message = "cannot be negative") @Max(value = 10000, message = "is too large") Integer maxUsers,
			@NotEmpty(message = "needs at least one module") Set<String> modules,
			@Size(max = 400) String notes) {
	}

	/** A client with the figures a product admin wants at a glance. */
	public record ClientView(Tenant client, long activeUsers, long documents) {
	}

	/** Returned once, when a client is created or a password reset: the only time the password is visible. */
	public record Credentials(Tenant client, String email, String temporaryPassword, boolean passwordGenerated) {
	}

	public record ClientUser(Long id, String email, String fullName, String role, boolean active) {
	}

	public record AddUser(
			@NotBlank(message = "is required") @Size(max = 100) String fullName,
			@NotBlank(message = "is required") @Email(message = "is not a valid email address") String email,
			@NotNull(message = "is required") Role role,
			@Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password) {
	}

	public record SupportSession(String token, String client, String email, String fullName, String role, long expiresInMinutes) {
	}

	// ---- read ----

	@Transactional(readOnly = true)
	public List<ClientView> list() {
		Map<String, Long> users = countByTenant("select tenant_id, count(*) from app_user where active = true group by tenant_id");
		Map<String, Long> documents = countByTenant("select tenant_id, count(*) from trade_document group by tenant_id");
		return tenants.findAll(Sort.by("name")).stream()
				.map(t -> new ClientView(t, users.getOrDefault(t.getSlug(), 0L), documents.getOrDefault(t.getSlug(), 0L))).toList();
	}

	@Transactional(readOnly = true)
	public List<ClientUser> users(Long clientId) {
		Tenant client = registry.require(clientId);
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery("select id, email, full_name, role, active from app_user where tenant_id = ?1 order by id")
				.setParameter(1, client.getSlug()).getResultList();
		return rows.stream().map(PlatformService::toUser).toList();
	}

	// ---- onboarding ----

	public Credentials create(CreateClient request) {
		if (RESERVED_SLUGS.contains(request.slug())) {
			throw ApiException.rule("SLUG_RESERVED", "'" + request.slug() + "' is reserved. Choose another short name.");
		}
		if (tenants.existsBySlug(request.slug())) {
			throw ApiException.conflict("DUPLICATE", "A client with the short name '" + request.slug() + "' already exists");
		}
		String email = request.ownerEmail().trim().toLowerCase();
		if (emailInUse(email)) {
			throw ApiException.conflict("DUPLICATE_EMAIL", email + " already signs in to another account. Each email can belong to one client only.");
		}
		String gstin = request.gstin() == null || request.gstin().isBlank() ? null : request.gstin();
		if (gstin != null && !gstin.startsWith(request.stateCode())) {
			throw ApiException.rule("GSTIN_STATE_MISMATCH", "The first two digits of the GSTIN must match the state code");
		}
		// No modules picked: switch on what the chosen industry normally uses.
		Set<String> modules = request.modules() == null || request.modules().isEmpty() ? Set.copyOf(request.industry().modules()) : request.modules();
		requireKnownModules(modules);

		Tenant client = new Tenant();
		client.setSlug(request.slug());
		client.setName(request.name().trim());
		client.setIndustry(request.industry());
		client.setWhiteLabel(Boolean.TRUE.equals(request.whiteLabel()));
		client.setHosting(request.hosting() == null ? Tenant.Hosting.SHARED : request.hosting());
		client.setMaxUsers(request.maxUsers() == null ? 0 : request.maxUsers());
		client.setModules(modules);
		client.setNotes(request.notes());
		client.setCreatedBy(CurrentUser.email());
		tenants.saveAndFlush(client);
		registry.forget(client.getSlug());

		boolean generated = request.ownerPassword() == null || request.ownerPassword().isBlank();
		String password = generated ? generatePassword() : request.ownerPassword();
		CompanyProfile company = new CompanyProfile();
		company.setLegalName(client.getName());
		company.setStateCode(request.stateCode());
		company.setGstin(gstin);
		// If this fails the client row above is rolled back with it, so a half-made client never exists.
		registry.runAs(client.getSlug(), () -> {
			provisioner.provisionClient(company, request.industry(), List.of(new UserSpec(email, request.ownerName().trim(), Role.OWNER, password)));
			return null;
		});
		audit.note(ENTITY, client.getId(), "CREATE", Map.of("slug", client.getSlug(), "name", client.getName(), "industry", client.getIndustry(), "owner", email));
		return new Credentials(client, email, password, generated);
	}

	public Tenant update(Long id, UpdateClient request) {
		Tenant client = registry.require(id);
		requireKnownModules(request.modules());
		long active = countByTenant("select tenant_id, count(*) from app_user where active = true group by tenant_id").getOrDefault(client.getSlug(), 0L);
		if (request.maxUsers() > 0 && request.maxUsers() < active) {
			throw ApiException.rule("LIMIT_BELOW_USAGE", "This client already has " + active + " active users; the limit cannot be set lower than that");
		}
		Map<String, Object> before = snapshot(client);
		client.setName(request.name().trim());
		client.setIndustry(request.industry());
		client.setStatus(request.status());
		client.setHosting(request.hosting());
		client.setWhiteLabel(request.whiteLabel());
		client.setMaxUsers(request.maxUsers());
		client.setModules(request.modules());
		client.setNotes(request.notes());
		Tenant saved = tenants.saveAndFlush(client);
		registry.forget(saved.getSlug());
		Map<String, Object> changes = new LinkedHashMap<>();
		snapshot(saved).forEach((key, value) -> {
			if (!String.valueOf(value).equals(String.valueOf(before.get(key)))) {
				changes.put(key, Map.of("from", String.valueOf(before.get(key)), "to", String.valueOf(value)));
			}
		});
		if (!changes.isEmpty()) {
			audit.note(ENTITY, id, "UPDATE", changes);
		}
		return saved;
	}

	// ---- support ----

	/**
	 * Starts a short support session as one of the client's users, or as its first active Owner
	 * when no user is named. The session carries the admin's identity, which is written beside
	 * every change made in it.
	 */
	public SupportSession impersonate(Long clientId, Long userId) {
		Tenant client = registry.require(clientId);
		if (!client.isActive()) {
			throw ApiException.conflict("TENANT_SUSPENDED", client.getName() + " is suspended. Reactivate it before starting a support session.");
		}
		ClientUser user = userId == null
				? users(clientId).stream().filter(u -> u.active() && u.role().equals(Role.OWNER.name())).findFirst()
						.orElseThrow(() -> ApiException.rule("NO_ACTIVE_OWNER", client.getName() + " has no active Owner to sign in as"))
				: users(clientId).stream().filter(u -> u.id().equals(userId)).findFirst()
						.orElseThrow(() -> ApiException.notFound("User", userId));
		if (!user.active()) {
			throw ApiException.rule("USER_INACTIVE", user.email() + " is deactivated and cannot be signed in as");
		}
		String token = tokens.issue(user.email(), user.fullName(), Role.valueOf(user.role()), client.getSlug(), CurrentUser.email(), SUPPORT_SESSION);
		audit.note(ENTITY, clientId, "IMPERSONATE", Map.of("client", client.getSlug(), "as", user.email(), "role", user.role()));
		return new SupportSession(token, client.getName(), user.email(), user.fullName(), user.role(), SUPPORT_SESSION.toMinutes());
	}

	/** Creates a user for a client on its behalf, with any client role. The password is shown to the admin once. */
	public Credentials addUser(Long clientId, AddUser request) {
		Tenant client = registry.require(clientId);
		if (request.role() == Role.PLATFORM_ADMIN) {
			throw ApiException.rule("ROLE_NOT_ALLOWED", "The product admin role cannot be given to a client's user");
		}
		String email = request.email().trim().toLowerCase();
		if (emailInUse(email)) {
			throw ApiException.conflict("DUPLICATE_EMAIL", email + " already signs in to another account. Each email can belong to one client only.");
		}
		long active = users(clientId).stream().filter(ClientUser::active).count();
		if (client.getMaxUsers() > 0 && active >= client.getMaxUsers()) {
			throw ApiException.rule("USER_LIMIT_REACHED", client.getName() + " is allowed " + client.getMaxUsers() + " active users. Raise the user limit first.");
		}
		boolean generated = request.password() == null || request.password().isBlank();
		String password = generated ? generatePassword() : request.password();
		registry.runAs(client.getSlug(), () -> {
			provisioner.addUser(new UserSpec(email, request.fullName().trim(), request.role(), password));
			return null;
		});
		audit.note(ENTITY, clientId, "ADD_USER", Map.of("client", client.getSlug(), "user", email, "role", request.role()));
		return new Credentials(client, email, password, generated);
	}

	/** Gives a client user a new temporary password, shown to the admin once. */
	public Credentials resetPassword(Long clientId, Long userId) {
		Tenant client = registry.require(clientId);
		ClientUser user = users(clientId).stream().filter(u -> u.id().equals(userId)).findFirst()
				.orElseThrow(() -> ApiException.notFound("User", userId));
		String password = generatePassword();
		em.createNativeQuery("update app_user set password_hash = ?1, updated_at = ?2 where id = ?3 and tenant_id = ?4")
				.setParameter(1, passwordEncoder.encode(password)).setParameter(2, Instant.now()).setParameter(3, userId)
				.setParameter(4, client.getSlug()).executeUpdate();
		audit.note(ENTITY, clientId, "RESET_PASSWORD", Map.of("client", client.getSlug(), "user", user.email()));
		return new Credentials(client, user.email(), password, true);
	}

	// ---- internals ----

	private boolean emailInUse(String email) {
		Number count = (Number) em.createNativeQuery("select count(*) from app_user where lower(email) = ?1").setParameter(1, email).getSingleResult();
		return count.longValue() > 0;
	}

	private Map<String, Long> countByTenant(String sql) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(sql).getResultList();
		Map<String, Long> counts = new LinkedHashMap<>();
		rows.forEach(r -> counts.put((String) r[0], ((Number) r[1]).longValue()));
		return counts;
	}

	private static void requireKnownModules(Set<String> modules) {
		List<String> unknown = new ArrayList<>(modules);
		unknown.removeAll(Tenant.MODULES);
		if (!unknown.isEmpty()) {
			throw ApiException.badRequest("UNKNOWN_MODULE", "Unknown module: " + String.join(", ", unknown));
		}
	}

	private static Map<String, Object> snapshot(Tenant t) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("name", t.getName());
		values.put("industry", t.getIndustry());
		values.put("status", t.getStatus());
		values.put("hosting", t.getHosting());
		values.put("whiteLabel", t.isWhiteLabel());
		values.put("maxUsers", t.getMaxUsers());
		values.put("modules", String.join(",", t.getModules()));
		return values;
	}

	private static ClientUser toUser(Object[] r) {
		return new ClientUser(((Number) r[0]).longValue(), (String) r[1], (String) r[2], (String) r[3], Boolean.TRUE.equals(r[4]));
	}

	private String generatePassword() {
		StringBuilder password = new StringBuilder();
		for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
			password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
		}
		return password.toString();
	}
}
