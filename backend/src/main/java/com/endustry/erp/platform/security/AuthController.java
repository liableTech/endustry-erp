package com.endustry.erp.platform.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.labels.LabelService;
import com.endustry.erp.platform.labels.Terms;
import com.endustry.erp.platform.labels.Terms.Term;
import com.endustry.erp.platform.security.SecurityRepositories.AppUserRepository;
import com.endustry.erp.platform.settings.SettingsController.Branding;
import com.endustry.erp.platform.settings.SettingsService;
import com.endustry.erp.platform.tenancy.Tenant;
import com.endustry.erp.platform.tenancy.TenantContext;
import com.endustry.erp.platform.tenancy.TenantRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokens;
	private final PermissionService permissions;
	private final TenantRegistry tenants;
	private final SettingsService settings;
	private final LabelService labels;

	@PersistenceContext
	private EntityManager em;

	@Value("${erp.security.token-hours:12}")
	private long tokenHours;

	public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
	}

	public record UserInfo(Long id, String email, String fullName, Role role) {
	}

	/** The client the user belongs to and what is switched on for it; null for a product admin. */
	public record ClientInfo(String slug, String name, boolean whiteLabel, Set<String> modules) {

		static ClientInfo of(Tenant t) {
			return new ClientInfo(t.getSlug(), t.getName(), t.isWhiteLabel(), t.getModules());
		}
	}

	/** {@code labels} are the client's words for customers, items and the rest (see {@link LabelService}). */
	public record Session(String token, UserInfo user, Map<String, AccessLevel> permissions, ClientInfo client, String impersonatedBy,
			Map<String, Term> labels) {
	}

	@PostMapping("/login")
	public Session login(@Valid @RequestBody LoginRequest request) {
		// Nobody is signed in yet, so the client is not known: an email is unique across all
		// clients, and finding its owner is the one lookup that has to span them.
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(
				"select id, email, full_name, role, active, password_hash, tenant_id from app_user where lower(email) = lower(?1)")
				.setParameter(1, request.email().trim()).getResultList();
		// Same answer for unknown user, wrong password and disabled account, so none can be probed.
		Object[] row = rows.stream().filter(r -> Boolean.TRUE.equals(r[4]))
				.filter(r -> passwordEncoder.matches(request.password(), (String) r[5])).findFirst()
				.orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect"));
		UserInfo user = new UserInfo(((Number) row[0]).longValue(), (String) row[1], (String) row[2], Role.valueOf((String) row[3]));
		String tenant = (String) row[6];
		String token = tokens.issue(user.email(), user.fullName(), user.role(), tenant, null, Duration.ofHours(tokenHours));
		if (TenantContext.PLATFORM.equals(tenant)) {
			return new Session(token, user, platformPermissions(), null, null, Terms.defaults(null));
		}
		Tenant client = tenants.find(tenant).orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect"));
		if (!client.isActive()) {
			throw ApiException.forbidden("TENANT_SUSPENDED", "This account is suspended. Please contact your provider.");
		}
		Map<String, AccessLevel> access = tenants.runAs(tenant, () -> permissions.forRole(user.role()));
		return new Session(token, user, access, ClientInfo.of(client), null, tenants.runAs(tenant, labels::current));
	}

	@GetMapping("/me")
	public Session me() {
		AppUser found = users.findByEmailIgnoreCase(CurrentUser.email()).filter(AppUser::isActive)
				.orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Please sign in to continue"));
		UserInfo user = new UserInfo(found.getId(), found.getEmail(), found.getFullName(), found.getRole());
		if (TenantContext.isPlatform()) {
			return new Session(null, user, platformPermissions(), null, null, Terms.defaults(null));
		}
		return new Session(null, user, permissions.forRole(user.role()), tenants.current().map(ClientInfo::of).orElse(null), CurrentUser.impersonator(),
				labels.current());
	}

	/**
	 * The look of the application for the signed-in user: a white-label client's own theme,
	 * otherwise the product's.
	 */
	@GetMapping("/branding")
	public Branding branding() {
		boolean ownTheme = TenantContext.isPlatform() || tenants.current().map(Tenant::isWhiteLabel).orElse(false);
		var company = settings.company();
		String name = company.getTradeName() == null || company.getTradeName().isBlank() ? company.getLegalName() : company.getTradeName();
		return new Branding(ownTheme ? settings.theme() : tenants.runAs(TenantContext.PLATFORM, settings::theme), name, company.getStateCode());
	}

	private static Map<String, AccessLevel> platformPermissions() {
		return Map.of(PermissionService.PLATFORM_MODULE, AccessLevel.FULL);
	}
}
