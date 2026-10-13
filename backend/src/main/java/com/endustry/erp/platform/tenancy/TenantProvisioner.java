package com.endustry.erp.platform.tenancy;

import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.masters.MasterRepositories.LookupRepository;
import com.endustry.erp.masters.MasterRepositories.WarehouseRepository;
import com.endustry.erp.masters.SimpleMasters.Lookup;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.security.AccessLevel;
import com.endustry.erp.platform.security.AppUser;
import com.endustry.erp.platform.security.PermissionService;
import com.endustry.erp.platform.security.Role;
import com.endustry.erp.platform.security.RolePermission;
import com.endustry.erp.platform.security.SecurityRepositories.AppUserRepository;
import com.endustry.erp.platform.security.SecurityRepositories.RolePermissionRepository;
import com.endustry.erp.platform.settings.CompanyProfile;
import com.endustry.erp.platform.settings.NumberSeries;
import com.endustry.erp.platform.settings.Parameter;
import com.endustry.erp.platform.settings.SettingsRepositories.CompanyProfileRepository;
import com.endustry.erp.platform.settings.SettingsRepositories.NumberSeriesRepository;
import com.endustry.erp.platform.settings.SettingsRepositories.ParameterRepository;
import com.endustry.erp.platform.settings.SettingsRepositories.ThemeSettingsRepository;
import com.endustry.erp.platform.settings.ThemeSettings;

import lombok.RequiredArgsConstructor;

/**
 * Sets up everything a new client needs to start working: company, theme, rules, numbering,
 * dropdown lists (units, item categories, rejection reasons) for their industry, the role matrix, a first warehouse and their users.
 * Always called inside {@link TenantRegistry#runAs}, so every row lands in that client's partition.
 */
@Service
@RequiredArgsConstructor
public class TenantProvisioner {

	private static final AccessLevel FULL = AccessLevel.FULL;
	private static final AccessLevel VIEW = AccessLevel.VIEW;

	public record UserSpec(String email, String fullName, Role role, String password) {
	}

	private final CompanyProfileRepository companies;
	private final ThemeSettingsRepository themes;
	private final ParameterRepository parameters;
	private final NumberSeriesRepository series;
	private final LookupRepository lookups;
	private final RolePermissionRepository permissions;
	private final AppUserRepository users;
	private final WarehouseRepository warehouses;
	private final PasswordEncoder passwordEncoder;

	/** A full client workspace. */
	public void provisionClient(CompanyProfile company, Industry industry, List<UserSpec> firstUsers) {
		companies.save(company);
		themes.save(new ThemeSettings());
		seedParameters();
		seedSeries();
		seedLookups(industry);
		seedPermissions();
		Warehouse main = new Warehouse();
		main.setCode("MAIN");
		main.setName("Main Store");
		warehouses.save(main);
		firstUsers.forEach(this::addUser);
	}

	/** The product owner's own space: just the default branding and the product admins. */
	public void provisionPlatform(CompanyProfile company, List<UserSpec> admins) {
		companies.save(company);
		themes.save(new ThemeSettings());
		admins.forEach(this::addUser);
	}

	/** Adds one user to the client the caller is running as. */
	public void addUser(UserSpec spec) {
		AppUser user = new AppUser();
		user.setEmail(spec.email().trim().toLowerCase());
		user.setFullName(spec.fullName());
		user.setRole(spec.role());
		user.setPasswordHash(passwordEncoder.encode(spec.password()));
		users.save(user);
	}

	private void seedParameters() {
		List.of(
				Parameter.of("sales.creditCheckMode", "BLOCK", "BLOCK|WARN|OFF", "Sales", "Credit limit check",
						"BLOCK stops an order that takes the customer past their credit limit (an Owner can override). WARN only flags it."),
				Parameter.of("sales.quotationValidityDays", "30", "NUMBER", "Sales", "Quotation validity (days)",
						"Used when a quotation is sent without a valid-until date."),
				Parameter.of("purchase.approvalLimit", "500000", "NUMBER", "Purchase", "PO approval limit (Rs)",
						"Purchase orders above this value can be approved only by the Owner."),
				Parameter.of("purchase.grnTolerancePct", "5", "NUMBER", "Purchase", "Over-receipt tolerance (%)",
						"How much more than the ordered quantity a goods receipt may accept."),
				Parameter.of("purchase.rateVarianceTolerancePct", "2", "NUMBER", "Purchase", "Invoice rate variance tolerance (%)",
						"A vendor invoice whose rate differs from the order by more than this is flagged."),
				Parameter.of("inventory.allowNegativeStock", "false", "BOOLEAN", "Inventory", "Allow negative stock",
						"When off, stock can never be issued or sold beyond what is on hand."),
				Parameter.of("gst.ewayBillThreshold", "50000", "NUMBER", "Compliance", "E-way bill threshold (Rs)",
						"Invoices of goods above this value are flagged as needing an e-way bill. Verify against current notifications."),
				Parameter.of("msme.maxCreditDays", "45", "NUMBER", "Compliance", "MSME maximum payment days",
						"MSMED Act: the longest period that can be agreed with a micro or small supplier."),
				Parameter.of("msme.defaultCreditDays", "15", "NUMBER", "Compliance", "MSME payment days without agreement",
						"MSMED Act: due period when no payment terms are agreed in writing."))
				.forEach(parameters::save);
	}

	private void seedSeries() {
		Map<DocKind, String> prefixes = Map.of(DocKind.QUOTATION, "QT", DocKind.SALES_ORDER, "SO", DocKind.SALES_INVOICE, "INV",
				DocKind.PURCHASE_ORDER, "PO", DocKind.GRN, "GRN", DocKind.PURCHASE_INVOICE, "PI");
		prefixes.forEach((kind, prefix) -> series.save(NumberSeries.of(kind.name(), prefix)));
		series.save(NumberSeries.of("WORK_ORDER", "WO"));
		series.save(NumberSeries.of("INSPECTION", "QC"));
	}

	private void seedLookups(Industry industry) {
		lookupList("UOM", industry.units());
		lookupList("ITEM_CATEGORY", industry.itemCategories());
		lookupList("REJECTION_REASON", industry.rejectionReasons());
		lookupList("DOWNTIME_REASON", List.of("NO_MATERIAL", "No material", "NO_POWER", "No power", "BREAKDOWN", "Machine breakdown",
				"NO_OPERATOR", "No operator", "SETUP", "Setup or changeover", "QUALITY_HOLD", "Quality hold"));
		lookupList("ADJUSTMENT_REASON", List.of("OPENING", "Opening stock", "COUNT", "Physical count difference", "DAMAGE", "Damaged or expired",
				"SAMPLE", "Issued as sample"));
		lookupList("STATE", List.of("07", "Delhi", "08", "Rajasthan", "09", "Uttar Pradesh", "19", "West Bengal", "23", "Madhya Pradesh",
				"24", "Gujarat", "27", "Maharashtra", "29", "Karnataka", "32", "Kerala", "33", "Tamil Nadu", "36", "Telangana",
				"37", "Andhra Pradesh", "03", "Punjab", "06", "Haryana", "10", "Bihar", "21", "Odisha"));
	}

	private void lookupList(String type, List<String> codesAndNames) {
		for (int i = 0; i < codesAndNames.size(); i += 2) {
			lookups.save(Lookup.of(type, codesAndNames.get(i), codesAndNames.get(i + 1), i / 2 + 1));
		}
	}

	/** The role x module matrix of BRD section 7.2, as a starting point the Owner can edit. */
	private void seedPermissions() {
		PermissionService.MODULES.forEach(m -> grant(Role.OWNER, m, FULL));
		grant(Role.ACCOUNTS, Map.of("dashboard", VIEW, "masters", VIEW, "sales", FULL, "purchase", FULL, "inventory", VIEW, "reports", VIEW));
		grant(Role.SALES, Map.of("dashboard", VIEW, "masters", VIEW, "sales", FULL, "inventory", VIEW, "production", VIEW, "quality", VIEW, "reports", VIEW));
		grant(Role.PURCHASE, Map.of("dashboard", VIEW, "masters", VIEW, "purchase", FULL, "inventory", VIEW, "production", VIEW, "quality", VIEW, "reports", VIEW));
		grant(Role.STORE, Map.of("dashboard", VIEW, "masters", VIEW, "sales", VIEW, "purchase", VIEW, "inventory", FULL, "production", VIEW, "quality", VIEW));
		grant(Role.PRODUCTION, Map.of("dashboard", VIEW, "masters", VIEW, "sales", VIEW, "purchase", VIEW, "inventory", VIEW, "production", FULL, "quality", VIEW, "reports", VIEW));
		grant(Role.SUPERVISOR, Map.of("dashboard", VIEW, "masters", VIEW, "inventory", VIEW, "production", FULL, "quality", VIEW));
		grant(Role.QUALITY, Map.of("dashboard", VIEW, "masters", VIEW, "inventory", VIEW, "production", VIEW, "quality", FULL, "reports", VIEW));
		grant(Role.CA, Map.of("dashboard", VIEW, "masters", VIEW, "sales", VIEW, "purchase", VIEW, "inventory", VIEW, "reports", VIEW));
	}

	private void grant(Role role, Map<String, AccessLevel> levels) {
		PermissionService.MODULES.forEach(m -> grant(role, m, levels.getOrDefault(m, AccessLevel.NONE)));
	}

	private void grant(Role role, String module, AccessLevel level) {
		permissions.save(RolePermission.of(role, module, level));
	}
}
