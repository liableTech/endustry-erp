package com.endustry.erp.seed;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.endustry.erp.platform.security.Role;
import com.endustry.erp.platform.settings.CompanyProfile;
import com.endustry.erp.platform.settings.SettingsRepositories.CompanyProfileRepository;
import com.endustry.erp.platform.tenancy.Industry;
import com.endustry.erp.platform.tenancy.Tenant;
import com.endustry.erp.platform.tenancy.TenantContext;
import com.endustry.erp.platform.tenancy.TenantProvisioner;
import com.endustry.erp.platform.tenancy.TenantProvisioner.UserSpec;
import com.endustry.erp.platform.tenancy.TenantRegistry;
import com.endustry.erp.platform.tenancy.TenantRegistry.TenantRepository;
import com.endustry.erp.platform.validation.GstinValidator;

import lombok.RequiredArgsConstructor;

/**
 * First-start setup. Creates the product owner's space with one product admin, and, when asked,
 * a demo client with one user per role and six months of sample transactions. Each step is
 * skipped once its data exists, so nothing entered later is ever overwritten.
 */
@Component
@RequiredArgsConstructor
public class StartupSeeder implements ApplicationRunner {

	public static final String DEMO_CLIENT = "demo";

	private final TenantRegistry registry;
	private final TenantRepository tenants;
	private final TenantProvisioner provisioner;
	private final CompanyProfileRepository companies;
	private final DemoDataSeeder demoData;

	@Value("${erp.seed.password}")
	private String seedPassword;

	@Value("${erp.seed.admin-email:admin@endustry.local}")
	private String adminEmail;

	@Value("${erp.seed.demo-client:true}")
	private boolean createDemoClient;

	@Value("${erp.seed.demo-data:false}")
	private boolean loadDemoData;

	@Override
	public void run(ApplicationArguments args) {
		registry.runAs(TenantContext.PLATFORM, () -> {
			if (companies.count() == 0) {
				CompanyProfile product = new CompanyProfile();
				product.setLegalName("Endustry ERP");
				product.setStateCode("27");
				provisioner.provisionPlatform(product, List.of(new UserSpec(adminEmail, "Product Admin", Role.PLATFORM_ADMIN, seedPassword)));
			}
			return null;
		});
		if (!createDemoClient) {
			return;
		}
		if (!tenants.existsBySlug(DEMO_CLIENT)) {
			Tenant demo = new Tenant();
			demo.setSlug(DEMO_CLIENT);
			demo.setName("Shree Precision Components Pvt Ltd");
			demo.setIndustry(Industry.ENGINEERING);
			demo.setWhiteLabel(true);
			demo.setNotes("Sample client with demonstration data");
			demo.setCreatedBy("system");
			tenants.save(demo);
			registry.forget(DEMO_CLIENT);
			registry.runAs(DEMO_CLIENT, () -> {
				// A database from before clients existed already holds this workspace; adopt it as it is.
				if (companies.count() == 0) {
					provisioner.provisionClient(demoCompany(), Industry.ENGINEERING, demoUsers());
				}
				return null;
			});
		}
		if (loadDemoData) {
			TenantContext.set(DEMO_CLIENT);
			try {
				demoData.seed();
			} finally {
				TenantContext.clear();
			}
		}
	}

	private static CompanyProfile demoCompany() {
		CompanyProfile c = new CompanyProfile();
		c.setLegalName("Shree Precision Components Pvt Ltd");
		c.setTradeName("Shree Precision");
		c.setPan("AAKCS4821P");
		c.setStateCode("27");
		c.setGstin(GstinValidator.withCheckDigit("27AAKCS4821P1Z"));
		c.setUdyamNumber("UDYAM-MH-26-0041873");
		c.setMsmeCategory("SMALL");
		c.setAddress("Plot 14, MIDC Bhosari");
		c.setCity("Pune");
		c.setPincode("411026");
		c.setPhone("020 5550 0142");
		c.setEmail("accounts@shreeprecision.example");
		return c;
	}

	/** One user per client role, e.g. owner@endustry.local, all with the seed password. */
	private List<UserSpec> demoUsers() {
		Map<Role, String> names = Map.of(Role.OWNER, "Rajesh Kulkarni", Role.ACCOUNTS, "Meena Joshi", Role.SALES, "Amit Deshmukh",
				Role.PURCHASE, "Sunil Patil", Role.STORE, "Ganesh Pawar", Role.PRODUCTION, "Vikram Shinde",
				Role.SUPERVISOR, "Ramesh Jadhav", Role.QUALITY, "Priya Nair", Role.CA, "Kedar Apte");
		return Arrays.stream(Role.clientRoles())
				.map(role -> new UserSpec(role.name().toLowerCase() + "@endustry.local", names.get(role), role, seedPassword)).toList();
	}
}
