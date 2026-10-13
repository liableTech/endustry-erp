package com.endustry.erp.platform.security;

import com.endustry.erp.platform.tenancy.TenantRegistry;
import com.endustry.erp.platform.tenancy.Tenant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.audit.AuditLog;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.common.CrudController;
import com.endustry.erp.platform.common.CrudService;
import com.endustry.erp.platform.common.PageResponse;
import com.endustry.erp.platform.security.SecurityRepositories.AppUserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@Service
class UserService extends CrudService<AppUser> {

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final TenantRegistry tenants;

	UserService(AppUserRepository users, PasswordEncoder passwordEncoder, TenantRegistry tenants) {
		super(users, "User", List.of("email", "fullName"));
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.tenants = tenants;
	}

	@Override
	protected String[] protectedFields() {
		return new String[] { "passwordHash" };
	}

	@Override
	protected void validate(AppUser incoming, AppUser existing) {
		incoming.setEmail(incoming.getEmail().trim().toLowerCase());
		if (incoming.getRole() == Role.PLATFORM_ADMIN) {
			throw ApiException.rule("ROLE_NOT_ALLOWED", "The product admin role cannot be given to a client's user");
		}
		// An email signs in to exactly one client, so it must be unique across all of them.
		Number sameEmail = (Number) em.createNativeQuery("select count(*) from app_user where lower(email) = ?1 and id <> ?2")
				.setParameter(1, incoming.getEmail()).setParameter(2, existing == null ? -1L : existing.getId()).getSingleResult();
		if (sameEmail.longValue() > 0) {
			throw ApiException.conflict("DUPLICATE", "A user with this email already exists");
		}
		// The product admin may cap how many people a client can have signed up at once.
		int limit = tenants.current().map(Tenant::getMaxUsers).orElse(0);
		boolean addsActiveUser = incoming.isActive() && (existing == null || !existing.isActive());
		if (limit > 0 && addsActiveUser && users.countByActiveTrue() >= limit) {
			throw ApiException.rule("USER_LIMIT_REACHED", "Your plan allows " + limit + " active users. Deactivate a user or ask your provider to raise the limit.");
		}
		if (existing == null) {
			if (incoming.getPassword() == null || incoming.getPassword().isBlank()) {
				throw ApiException.badRequest("PASSWORD_REQUIRED", "A password is required for a new user");
			}
			incoming.setPasswordHash(passwordEncoder.encode(incoming.getPassword()));
			return;
		}
		boolean losesOwner = existing.getRole() == Role.OWNER && existing.isActive()
				&& (incoming.getRole() != Role.OWNER || !incoming.isActive());
		if (losesOwner && users.countByRoleAndActiveTrue(Role.OWNER) <= 1) {
			throw ApiException.rule("LAST_OWNER", "At least one active Owner is required");
		}
		if (incoming.getPassword() != null && !incoming.getPassword().isBlank()) {
			existing.setPasswordHash(passwordEncoder.encode(incoming.getPassword()));
		}
	}

	@Override
	public void delete(Long id) {
		throw ApiException.rule("USER_DELETE_BLOCKED", "Users are deactivated, not deleted, to keep the audit trail intact");
	}
}

@RestController
@RequestMapping("/api/admin/users")
class UserController extends CrudController<AppUser> {

	UserController(UserService service) {
		super(service);
	}
}

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
class AccessController {

	private final PermissionService permissions;
	private final AuditService audit;

	record PermissionChange(@NotNull Role role, @NotBlank String module, @NotNull AccessLevel level) {
	}

	record PermissionMatrix(List<String> modules, Role[] roles, Map<Role, Map<String, AccessLevel>> matrix) {
	}

	@GetMapping("/permissions")
	PermissionMatrix permissions() {
		return new PermissionMatrix(PermissionService.MODULES, Role.clientRoles(), permissions.matrix());
	}

	@PutMapping("/permissions")
	PermissionMatrix setPermission(@Valid @RequestBody PermissionChange change) {
		permissions.set(change.role(), change.module(), change.level());
		return permissions();
	}

	@GetMapping("/audit")
	PageResponse<AuditLog> audit(@RequestParam(required = false) String entityType,
			@PageableDefault(size = 50, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(audit.list(entityType, pageable));
	}
}
