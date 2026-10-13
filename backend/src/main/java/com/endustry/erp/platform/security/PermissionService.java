package com.endustry.erp.platform.security;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.SecurityRepositories.RolePermissionRepository;
import com.endustry.erp.platform.tenancy.Tenant;
import com.endustry.erp.platform.tenancy.TenantContext;
import com.endustry.erp.platform.tenancy.TenantRegistry;

import lombok.RequiredArgsConstructor;

/** Each client's role x module access matrix, cached in memory and reloaded whenever it is edited. */
@Service
@RequiredArgsConstructor
public class PermissionService {

	/** Every protected area of the API. The first path segment after /api names the module. */
	public static final List<String> MODULES = List.of("dashboard", "masters", "sales", "purchase", "inventory",
			"production", "quality", "reports", "settings", "admin");

	/** The product admin area, open only to product admins. */
	public static final String PLATFORM_MODULE = "platform";

	private final RolePermissionRepository repo;
	private final AuditService audit;
	private final TenantRegistry tenants;

	private final Map<String, Map<Role, Map<String, AccessLevel>>> cache = new ConcurrentHashMap<>();

	/**
	 * What a role may do in a module for the current client. A module the product admin has
	 * switched off for the client is closed to every role, whatever the matrix says.
	 */
	public AccessLevel level(Role role, String module) {
		if (role == null || !moduleEnabled(module)) {
			return AccessLevel.NONE;
		}
		return matrix().getOrDefault(role, Map.of()).getOrDefault(module, AccessLevel.NONE);
	}

	public boolean moduleEnabled(String module) {
		return !Tenant.MODULES.contains(module) || tenants.current().map(t -> t.getModules().contains(module)).orElse(true);
	}

	public Map<String, AccessLevel> forRole(Role role) {
		Map<String, AccessLevel> result = new LinkedHashMap<>();
		MODULES.forEach(m -> result.put(m, level(role, m)));
		return result;
	}

	public Map<Role, Map<String, AccessLevel>> matrix() {
		return cache.computeIfAbsent(TenantContext.get(), tenant -> {
			Map<Role, Map<String, AccessLevel>> loaded = new EnumMap<>(Role.class);
			for (RolePermission p : repo.findAll()) {
				loaded.computeIfAbsent(p.getRole(), r -> new LinkedHashMap<>()).put(p.getModule(), p.getLevel());
			}
			return loaded;
		});
	}

	@Transactional
	public void set(Role role, String module, AccessLevel level) {
		if (!MODULES.contains(module)) {
			throw ApiException.badRequest("UNKNOWN_MODULE", "Unknown module '" + module + "'");
		}
		if (role == Role.PLATFORM_ADMIN) {
			throw ApiException.rule("ROLE_NOT_ALLOWED", "The product admin role is not part of a client's access matrix");
		}
		if (role == Role.OWNER && (module.equals("admin") || module.equals("settings")) && level != AccessLevel.FULL) {
			throw ApiException.rule("OWNER_LOCKOUT", "The Owner role must keep full access to administration and settings");
		}
		RolePermission p = repo.findByRoleAndModule(role, module).orElseGet(() -> RolePermission.of(role, module, level));
		AccessLevel previous = p.getId() == null ? null : p.getLevel();
		p.setLevel(level);
		repo.save(p);
		cache.remove(TenantContext.get());
		audit.note("RolePermission", p.getId(), "UPDATE",
				Map.of("role", role, "module", module, "from", String.valueOf(previous), "to", level));
	}
}
