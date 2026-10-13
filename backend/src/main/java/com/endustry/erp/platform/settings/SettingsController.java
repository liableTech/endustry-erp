package com.endustry.erp.platform.settings;

import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.tenancy.TenantRegistry;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SettingsController {

	private final SettingsService settings;
	private final TenantRegistry tenants;

	public record Branding(ThemeSettings theme, String companyName, String stateCode) {
	}

	public record ParameterValue(@NotNull String value) {
	}

	/**
	 * Needed before sign-in so the login page already carries the configured look. The tenant
	 * filter has already pointed this request at the product's branding or a white-label client's.
	 */
	@GetMapping("/public/branding")
	public Branding branding() {
		CompanyProfile company = settings.company();
		String name = company.getTradeName() == null || company.getTradeName().isBlank() ? company.getLegalName()
				: company.getTradeName();
		return new Branding(settings.theme(), name, company.getStateCode());
	}

	@GetMapping("/settings/company")
	public CompanyProfile company() {
		return settings.company();
	}

	@PutMapping("/settings/company")
	public CompanyProfile saveCompany(@Valid @RequestBody CompanyProfile body) {
		return settings.saveCompany(body);
	}

	@GetMapping("/settings/theme")
	public ThemeSettings theme() {
		return settings.theme();
	}

	@PutMapping("/settings/theme")
	public ThemeSettings saveTheme(@Valid @RequestBody ThemeSettings body) {
		// Own branding is a feature the product admin switches on per client.
		if (tenants.current().map(t -> !t.isWhiteLabel()).orElse(false)) {
			throw ApiException.forbidden("FEATURE_DISABLED", "Custom branding is not enabled for your account. Ask your provider to switch on white-label.");
		}
		return settings.saveTheme(body);
	}

	@GetMapping("/settings/parameters")
	public List<Parameter> parameters() {
		return settings.parameters();
	}

	@PutMapping("/settings/parameters/{id}")
	public Parameter setParameter(@PathVariable Long id, @Valid @RequestBody ParameterValue body) {
		return settings.setParameter(id, body.value());
	}

	@GetMapping("/settings/number-series")
	public List<NumberSeries> series() {
		return settings.allSeries();
	}

	@PutMapping("/settings/number-series/{id}")
	public NumberSeries saveSeries(@PathVariable Long id, @Valid @RequestBody NumberSeries body) {
		return settings.saveSeries(id, body);
	}
}
