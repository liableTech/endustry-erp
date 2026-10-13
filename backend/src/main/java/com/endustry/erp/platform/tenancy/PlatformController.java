package com.endustry.erp.platform.tenancy;

import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.audit.AuditLog;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.PageResponse;
import com.endustry.erp.platform.settings.SettingsService;
import com.endustry.erp.platform.settings.ThemeSettings;
import com.endustry.erp.platform.security.Role;
import com.endustry.erp.platform.tenancy.PlatformService.AddUser;
import com.endustry.erp.platform.tenancy.PlatformService.ClientUser;
import com.endustry.erp.platform.tenancy.PlatformService.ClientView;
import com.endustry.erp.platform.tenancy.PlatformService.CreateClient;
import com.endustry.erp.platform.tenancy.PlatformService.Credentials;
import com.endustry.erp.platform.tenancy.PlatformService.SupportSession;
import com.endustry.erp.platform.tenancy.PlatformService.UpdateClient;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** The product admin area. Only the PLATFORM_ADMIN role gets past the permission check for /api/platform. */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

	private final PlatformService platform;
	private final TenantRegistry registry;
	private final SettingsService settings;
	private final AuditService audit;

	/** An industry for the new client form, with the modules it switches on by default. */
	public record IndustryOption(String value, String label, String group, List<String> modules) {
	}

	public record Options(List<IndustryOption> industries, List<String> modules, Role[] roles) {
	}

	public record ImpersonateRequest(Long userId) {
	}

	@GetMapping("/options")
	public Options options() {
		return new Options(Arrays.stream(Industry.values()).map(i -> new IndustryOption(i.name(), i.label(), i.group().name(), i.modules())).toList(), Tenant.MODULES, Role.clientRoles());
	}

	@GetMapping("/clients")
	public List<ClientView> clients() {
		return platform.list();
	}

	@GetMapping("/clients/{id}")
	public Tenant client(@PathVariable Long id) {
		return registry.require(id);
	}

	@PostMapping("/clients")
	@ResponseStatus(HttpStatus.CREATED)
	public Credentials create(@Valid @RequestBody CreateClient body) {
		return platform.create(body);
	}

	@PutMapping("/clients/{id}")
	public Tenant update(@PathVariable Long id, @Valid @RequestBody UpdateClient body) {
		return platform.update(id, body);
	}

	@GetMapping("/clients/{id}/users")
	public List<ClientUser> users(@PathVariable Long id) {
		return platform.users(id);
	}

	@PostMapping("/clients/{id}/users")
	@ResponseStatus(HttpStatus.CREATED)
	public Credentials addUser(@PathVariable Long id, @Valid @RequestBody AddUser body) {
		return platform.addUser(id, body);
	}

	@PostMapping("/clients/{id}/impersonate")
	public SupportSession impersonate(@PathVariable Long id, @RequestBody(required = false) ImpersonateRequest body) {
		return platform.impersonate(id, body == null ? null : body.userId());
	}

	@PostMapping("/clients/{id}/users/{userId}/reset-password")
	public Credentials resetPassword(@PathVariable Long id, @PathVariable Long userId) {
		return platform.resetPassword(id, userId);
	}

	/** The product's own branding: what every client without white-label sees. */
	@GetMapping("/theme")
	public ThemeSettings theme() {
		return settings.theme();
	}

	@PutMapping("/theme")
	public ThemeSettings saveTheme(@Valid @RequestBody ThemeSettings body) {
		return settings.saveTheme(body);
	}

	/** What product admins have done: clients created, changed, suspended, and every support session. */
	@GetMapping("/audit")
	public PageResponse<AuditLog> audit(@PageableDefault(size = 50, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(audit.list(null, pageable));
	}
}
