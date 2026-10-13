package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;

import com.endustry.erp.platform.common.GlobalExceptionHandler;
import com.endustry.erp.platform.migration.V2__Widen_choice_columns;
import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** The product admin: bringing clients on, keeping them apart, switching features, and support sessions. */
class ProductAdminTest extends ApiTest {

	private static final Role ADMIN = Role.PLATFORM_ADMIN;
	private static final String CLIENTS = "/api/platform/clients";
	private static final String OWNER_PASSWORD = "First-Login-Pass-9";

	// ---- sign-in and separation ----

	@Test
	void theProductAdminSignsInToTheProductAreaOnly() {
		JsonNode session = post(200, null, "/api/auth/login", Map.of("email", email(ADMIN), "password", password));
		assertThat(session.path("user").path("role").asString()).isEqualTo("PLATFORM_ADMIN");
		assertThat(session.path("permissions").path("platform").asString()).isEqualTo("FULL");
		assertThat(session.path("client").isNull()).isTrue();

		assertThat(get(ADMIN, CLIENTS).toString()).contains("\"slug\":\"demo\"");
		assertThat(get(ADMIN, "/api/platform/options").path("industries").size()).isGreaterThanOrEqualTo(5);
		assertThat(get(ADMIN, "/api/auth/me").path("user").path("email").asString()).isEqualTo(email(ADMIN));
		// Client data is opened through a support session, never directly.
		assertCode(call(403, ADMIN, HttpMethod.GET, "/api/masters/items", null), "PLATFORM_ONLY");
		assertCode(call(403, ADMIN, HttpMethod.GET, "/api/dashboard/summary", null), "PLATFORM_ONLY");
	}

	@Test
	void clientUsersCannotOpenTheProductArea() {
		assertCode(call(403, Role.OWNER, HttpMethod.GET, CLIENTS, null), "FORBIDDEN");
		assertCode(post(403, Role.OWNER, CLIENTS, clientBody(unique("blocked"))), "FORBIDDEN");
		assertCode(post(403, Role.OWNER, CLIENTS + "/1/impersonate", null), "FORBIDDEN");
		assertCode(call(401, null, HttpMethod.GET, CLIENTS, null), "UNAUTHENTICATED");
	}

	// ---- onboarding ----

	@Test
	void aNewClientCanSignInAndWorkStraightAway() {
		String slug = unique("acme");
		Map<String, Object> body = clientBody(slug);
		body.put("industry", "PLASTICS");
		JsonNode created = post(201, ADMIN, CLIENTS, body);
		assertThat(created.path("client").path("slug").asString()).isEqualTo(slug);
		assertThat(created.path("client").path("status").asString()).isEqualTo("ACTIVE");
		assertThat(created.path("passwordGenerated").asBoolean()).isFalse();

		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);
		JsonNode me = callWithToken(200, owner, HttpMethod.GET, "/api/auth/me", null);
		assertThat(me.path("client").path("slug").asString()).isEqualTo(slug);
		assertThat(me.path("permissions").path("admin").asString()).isEqualTo("FULL");

		// The workspace is ready: company, a warehouse, numbering, and lists for the chosen industry.
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/settings/company", null).path("legalName").asString()).isEqualTo("Acme Plastics");
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/masters/warehouses", null).path("totalElements").asInt()).isEqualTo(1);
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/masters/lookups?lookupType=ITEM_CATEGORY&size=50", null).toString())
				.contains("Masterbatch").doesNotContain("Castings");
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/masters/lookups?lookupType=REJECTION_REASON&size=50", null).toString()).contains("Short shot");

		// They can create their first quotation, numbered from 1 in their own series.
		long item = callWithToken(201, owner, HttpMethod.POST, "/api/masters/items", itemBody("FINISHED")).path("id").asLong();
		long customer = callWithToken(201, owner, HttpMethod.POST, "/api/masters/parties", partyBody("CUSTOMER", "27")).path("id").asLong();
		JsonNode quote = callWithToken(201, owner, HttpMethod.POST, "/api/sales/docs/quotation", docBody(customer, null, line(item, 2, 500)));
		assertThat(quote.path("docNo").asString()).endsWith("/0001");
	}

	@Test
	void anIndustryFillsInItsUsualModulesAndLists() {
		JsonNode options = get(ADMIN, "/api/platform/options");
		assertThat(options.path("industries").toString()).contains("TRADING", "RETAIL", "SERVICES");

		// A services firm, no modules picked: it gets what services businesses use, not production or stock.
		Map<String, Object> body = clientBody(unique("svc"));
		body.put("industry", "SERVICES");
		JsonNode created = post(201, ADMIN, CLIENTS, body);
		assertThat(created.path("client").path("modules").toString()).contains("sales", "purchase", "reports").doesNotContain("production", "quality", "inventory");
		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/masters/lookups?lookupType=UOM&size=50", null).toString()).contains("Hours");
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/masters/lookups?lookupType=ITEM_CATEGORY&size=50", null).toString())
				.contains("Installation").doesNotContain("Castings");

		// A trader whose admin picked modules keeps that choice.
		body = merge(clientBody(unique("trd")), "industry", "TRADING");
		body.put("modules", List.of("sales", "inventory"));
		assertThat(post(201, ADMIN, CLIENTS, body).path("client").path("modules").toString()).contains("sales", "inventory").doesNotContain("purchase");
	}

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private V2__Widen_choice_columns widenChoiceColumns;

	@Test
	void aDatabaseFromBeforeANewIndustryAcceptsItAfterTheMigration() throws Exception {
		// A database created before Retail existed stored the column as a fixed list without it.
		jdbc.execute("alter table \"TENANT\" alter column \"INDUSTRY\" set data type "
				+ "enum('AUTO_COMPONENTS','ENGINEERING','FOOD','GENERAL','PLASTICS','TEXTILES','TRADING','SERVICES','OTHER')");
		Map<String, Object> body = merge(clientBody(unique("old")), "industry", "RETAIL");
		JsonNode refused = post(409, ADMIN, CLIENTS, body);
		assertCode(refused, "VALUE_NOT_ALLOWED");
		assertThat(refused.path("message").asString()).contains("RETAIL");

		try (Connection connection = jdbc.getDataSource().getConnection()) {
			widenChoiceColumns.apply(connection);
		}
		assertThat(post(201, ADMIN, CLIENTS, body).path("client").path("industry").asString()).isEqualTo("RETAIL");
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = 'PUBLIC' and data_type = 'ENUM'", Long.class)).isZero();
	}

	@Test
	void aDuplicateSaysWhichFieldClashed() {
		assertThat(GlobalExceptionHandler.duplicateField("Unique index or primary key violation: \"PUBLIC.UK1 INDEX PUBLIC.UK1_INDEX_2 ON PUBLIC.ITEM(TENANT_ID NULLS FIRST, CODE NULLS FIRST) VALUES ('demo', 'IT1')\""))
				.isEqualTo("code");
		assertThat(GlobalExceptionHandler.duplicateField("duplicate key value violates unique constraint \"uk\" Detail: Key (tenant_id, doc_no)=(demo, X) already exists."))
				.isEqualTo("doc no");
		assertThat(GlobalExceptionHandler.duplicateField("something else")).isEqualTo("code or number");
	}

	@Test
	void aPasswordIsGeneratedWhenNoneIsGiven() {
		Map<String, Object> body = clientBody(unique("gen"));
		body.remove("ownerPassword");
		JsonNode created = post(201, ADMIN, CLIENTS, body);
		assertThat(created.path("passwordGenerated").asBoolean()).isTrue();
		String generated = created.path("temporaryPassword").asString();
		assertThat(generated).hasSizeGreaterThanOrEqualTo(12);
		signIn(created.path("email").asString(), generated);
		// The password is shown once: it is not part of any later listing.
		assertThat(get(ADMIN, CLIENTS).toString()).doesNotContain(generated).doesNotContain("password");
	}

	@Test
	void rejectsAClientThatCannotBeCreated() {
		String slug = unique("dup");
		Map<String, Object> first = clientBody(slug);
		post(201, ADMIN, CLIENTS, first);

		assertCode(post(409, ADMIN, CLIENTS, merge(clientBody(slug), "ownerEmail", unique("other") + "@example.com")), "DUPLICATE");
		// An email signs in to exactly one client, including the demo client's users.
		assertCode(post(409, ADMIN, CLIENTS, merge(clientBody(unique("mail")), "ownerEmail", first.get("ownerEmail"))), "DUPLICATE_EMAIL");
		assertCode(post(409, ADMIN, CLIENTS, merge(clientBody(unique("mail")), "ownerEmail", email(Role.OWNER).toUpperCase())), "DUPLICATE_EMAIL");
		assertCode(post(422, ADMIN, CLIENTS, clientBody("platform")), "SLUG_RESERVED");
		assertCode(post(400, ADMIN, CLIENTS, merge(clientBody(unique("mod")), "modules", List.of("sales", "payroll"))), "UNKNOWN_MODULE");
		assertCode(post(400, ADMIN, CLIENTS, merge(clientBody(unique("ind")), "industry", "SPACE_TRAVEL")), "MALFORMED_REQUEST");
		assertCode(post(422, ADMIN, CLIENTS, merge(clientBody(unique("gst")), "gstin", gstin("24"), "stateCode", "27")), "GSTIN_STATE_MISMATCH");

		JsonNode invalid = post(400, ADMIN, CLIENTS, obj("name", "", "slug", "Bad Slug!", "stateCode", "MH", "ownerName", "", "ownerEmail", "nope",
				"ownerPassword", "short", "maxUsers", -1, "gstin", "NOT-A-GSTIN"));
		assertCode(invalid, "VALIDATION_FAILED");
		assertThat(invalid.path("fieldErrors").propertyNames())
				.contains("name", "slug", "industry", "stateCode", "ownerName", "ownerEmail", "ownerPassword", "maxUsers", "gstin");
	}

	@Test
	void aFailedCreationLeavesNothingBehind() {
		String slug = unique("ghost");
		int before = get(ADMIN, CLIENTS).size();
		assertCode(post(409, ADMIN, CLIENTS, merge(clientBody(slug), "ownerEmail", email(Role.STORE))), "DUPLICATE_EMAIL");
		assertThat(get(ADMIN, CLIENTS).size()).isEqualTo(before);
		// The short name is still free.
		post(201, ADMIN, CLIENTS, clientBody(slug));
	}

	// ---- isolation ----

	@Test
	void oneClientNeverSeesAnothersData() {
		String aToken = newClient(unique("iso-a"));
		String bToken = newClient(unique("iso-b"));

		// The same code in two clients is not a clash, and each sees only its own record.
		Map<String, Object> item = merge(itemBody("FINISHED"), "code", "SHARED-CODE-1", "name", "Client A widget");
		long aItem = callWithToken(201, aToken, HttpMethod.POST, "/api/masters/items", item).path("id").asLong();
		long bItem = callWithToken(201, bToken, HttpMethod.POST, "/api/masters/items", merge(item, "name", "Client B widget")).path("id").asLong();
		assertCode(callWithToken(409, aToken, HttpMethod.POST, "/api/masters/items", item), "DUPLICATE");

		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/masters/items?q=SHARED-CODE-1", null).path("totalElements").asInt()).isEqualTo(1);
		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/masters/items/" + aItem, null).path("name").asString()).isEqualTo("Client A widget");
		// Guessing another client's record id finds nothing, to read, change or delete.
		assertCode(callWithToken(404, aToken, HttpMethod.GET, "/api/masters/items/" + bItem, null), "NOT_FOUND");
		assertCode(callWithToken(404, aToken, HttpMethod.PUT, "/api/masters/items/" + bItem, item), "NOT_FOUND");
		assertCode(callWithToken(404, aToken, HttpMethod.DELETE, "/api/masters/items/" + bItem, null), "NOT_FOUND");
		assertThat(callWithToken(200, bToken, HttpMethod.GET, "/api/masters/items/" + bItem, null).path("name").asString()).isEqualTo("Client B widget");

		// Nor can a record of one client be used on another's document.
		long bCustomer = callWithToken(201, bToken, HttpMethod.POST, "/api/masters/parties", partyBody("CUSTOMER", "27")).path("id").asLong();
		assertCode(callWithToken(422, aToken, HttpMethod.POST, "/api/sales/docs/quotation", docBody(bCustomer, null, line(aItem, 1, 10))), "UNKNOWN_PARTY");

		// The demo client sees none of it; lists, dashboard, reports and audit trail stay separate.
		assertThat(get(Role.OWNER, "/api/masters/items?q=SHARED-CODE-1").path("totalElements").asInt()).isZero();
		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/dashboard/summary", null).path("recentDocuments").size()).isZero();
		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/reports/sales-register", null).path("rows").size()).isZero();
		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/admin/audit?size=100", null).toString()).doesNotContain("Client B widget");
		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/admin/users", null).path("totalElements").asInt()).isEqualTo(1);
	}

	@Test
	void eachClientHasItsOwnSettingsAndAccessMatrix() {
		String aToken = newClient(unique("set-a"));
		String bToken = newClient(unique("set-b"));

		JsonNode limit = null;
		for (JsonNode p : callWithToken(200, aToken, HttpMethod.GET, "/api/settings/parameters", null)) {
			if (p.path("paramKey").asString().equals("purchase.approvalLimit")) {
				limit = p;
			}
		}
		callWithToken(200, aToken, HttpMethod.PUT, "/api/settings/parameters/" + limit.path("id").asLong(), Map.of("value", "1000"));
		assertThat(callWithToken(200, bToken, HttpMethod.GET, "/api/settings/parameters", null).toString()).contains("\"paramValue\":\"500000\"").doesNotContain("\"paramValue\":\"1000\"");

		callWithToken(200, aToken, HttpMethod.PUT, "/api/admin/permissions", Map.of("role", "SALES", "module", "sales", "level", "VIEW"));
		assertThat(callWithToken(200, aToken, HttpMethod.GET, "/api/admin/permissions", null).path("matrix").path("SALES").path("sales").asString()).isEqualTo("VIEW");
		assertThat(callWithToken(200, bToken, HttpMethod.GET, "/api/admin/permissions", null).path("matrix").path("SALES").path("sales").asString()).isEqualTo("FULL");
		assertThat(callWithToken(200, bToken, HttpMethod.GET, "/api/admin/permissions", null).path("roles").toString()).doesNotContain("PLATFORM_ADMIN");
	}

	// ---- features per client ----

	@Test
	void aModuleSwitchedOffIsClosedToTheClient() {
		String slug = unique("mods");
		JsonNode created = post(201, ADMIN, CLIENTS, merge(clientBody(slug), "modules", List.of("sales", "inventory", "reports")));
		assertThat(created.path("client").path("modules").toString()).isEqualTo("[\"sales\",\"inventory\",\"reports\"]");
		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);

		JsonNode me = callWithToken(200, owner, HttpMethod.GET, "/api/auth/me", null);
		assertThat(me.path("permissions").path("sales").asString()).isEqualTo("FULL");
		assertThat(me.path("permissions").path("production").asString()).isEqualTo("NONE");
		callWithToken(200, owner, HttpMethod.GET, "/api/sales/docs/quotation", null);
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/production/boms", null), "MODULE_DISABLED");
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/quality/inspections", null), "MODULE_DISABLED");
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/purchase/docs/purchase-order", null), "MODULE_DISABLED");

		// Switching production on takes effect at once, without the client signing in again.
		long id = created.path("client").path("id").asLong();
		put(200, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "ACTIVE", false, 0, List.of("sales", "inventory", "reports", "production")));
		callWithToken(200, owner, HttpMethod.GET, "/api/production/boms", null);
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/quality/inspections", null), "MODULE_DISABLED");
	}

	@Test
	void aSwitchedOffModuleLeavesNoTraceOnTheDashboardReportsOrPrinting() {
		String slug = unique("trace");
		JsonNode created = post(201, ADMIN, CLIENTS, merge(clientBody(slug), "modules", List.of("purchase", "reports")));
		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);

		JsonNode summary = callWithToken(200, owner, HttpMethod.GET, "/api/dashboard/summary", null);
		assertThat(summary.path("modules").toString()).contains("purchase").doesNotContain("sales").doesNotContain("production");
		assertThat(summary.path("kpis").path("salesThisMonth").isNull()).isTrue();
		assertThat(summary.path("kpis").path("openWorkOrders").isNull()).isTrue();
		assertThat(summary.path("kpis").path("stockValue").isNull()).isTrue();
		assertThat(summary.path("kpis").path("payables").isNull()).isFalse();

		String reports = callWithToken(200, owner, HttpMethod.GET, "/api/reports", null).toString();
		assertThat(reports).contains("purchase-register", "gst-summary").doesNotContain("sales-register", "stock-summary", "production-summary");
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/reports/sales-register", null), "MODULE_DISABLED");
		callWithToken(200, owner, HttpMethod.GET, "/api/reports/gst-summary", null);

		String types = callWithToken(200, owner, HttpMethod.GET, "/api/settings/print-templates/types", null).toString();
		assertThat(types).contains("PURCHASE_ORDER").doesNotContain("SALES_INVOICE", "WORK_ORDER", "GRN");
		// Raising a vendor invoice from a goods receipt reads inventory, which is switched off.
		assertCode(callWithToken(403, owner, HttpMethod.POST, "/api/purchase/docs/purchase-invoice/from/1", null), "MODULE_DISABLED");

		// Unticking a module closes it to a client already signed in, on its next request.
		long id = created.path("client").path("id").asLong();
		put(200, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "ACTIVE", false, 0, List.of("reports")));
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/purchase/docs/purchase-order", null), "MODULE_DISABLED");
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/reports/purchase-register", null), "MODULE_DISABLED");
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/reports", null).size()).isZero();
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/auth/me", null).path("permissions").path("purchase").asString()).isEqualTo("NONE");
	}

	@Test
	void ownBrandingNeedsWhiteLabelSwitchedOn() {
		String slug = unique("brand");
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(slug));
		long id = created.path("client").path("id").asLong();
		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);
		Map<String, Object> theme = obj("appName", "Acme Factory", "mode", "light", "primaryColor", "#B91C1C", "accentColor", "#F59E0B",
				"successColor", "#16A34A", "warningColor", "#D97706", "dangerColor", "#DC2626", "sidebarStyle", "brand", "radius", 8,
				"density", "comfortable", "fontFamily", "Inter");

		// Without white-label the client cannot rebrand, and sees the product's theme.
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/auth/me", null).path("client").path("whiteLabel").asBoolean()).isFalse();
		assertCode(callWithToken(403, owner, HttpMethod.PUT, "/api/settings/theme", theme), "FEATURE_DISABLED");
		String productColour = get(ADMIN, "/api/platform/theme").path("primaryColor").asString();
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/auth/branding", null).path("theme").path("primaryColor").asString()).isEqualTo(productColour);
		assertThat(get(null, "/api/public/branding?client=" + slug).path("companyName").asString()).isEqualTo("Endustry ERP");

		// With it switched on, their theme is theirs, on their own sign-in link too.
		put(200, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "ACTIVE", true, 0, List.of("sales", "purchase", "inventory", "production", "quality", "reports")));
		callWithToken(200, owner, HttpMethod.PUT, "/api/settings/theme", theme);
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/auth/branding", null).path("theme").path("appName").asString()).isEqualTo("Acme Factory");
		assertThat(get(null, "/api/public/branding?client=" + slug).path("theme").path("primaryColor").asString()).isEqualTo("#B91C1C");
		// Other clients and the product itself are untouched.
		assertThat(get(ADMIN, "/api/platform/theme").path("primaryColor").asString()).isEqualTo(productColour);
		assertThat(get(null, "/api/public/branding").path("theme").path("appName").asString()).isNotEqualTo("Acme Factory");
	}

	@Test
	void theUserLimitIsEnforced() {
		JsonNode created = post(201, ADMIN, CLIENTS, merge(clientBody(unique("cap")), "maxUsers", 2));
		long id = created.path("client").path("id").asLong();
		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);

		long second = callWithToken(201, owner, HttpMethod.POST, "/api/admin/users", userBody("STORE")).path("id").asLong();
		assertCode(callWithToken(422, owner, HttpMethod.POST, "/api/admin/users", userBody("SALES")), "USER_LIMIT_REACHED");
		// An inactive user does not count, and deactivating one frees a place.
		Map<String, Object> inactive = merge(userBody("SALES"), "active", false);
		long third = callWithToken(201, owner, HttpMethod.POST, "/api/admin/users", inactive).path("id").asLong();
		assertCode(callWithToken(422, owner, HttpMethod.PUT, "/api/admin/users/" + third, merge(inactive, "active", true)), "USER_LIMIT_REACHED");
		callWithToken(200, owner, HttpMethod.PUT, "/api/admin/users/" + second, merge(userBody("STORE"), "active", false));
		callWithToken(200, owner, HttpMethod.PUT, "/api/admin/users/" + third, merge(inactive, "active", true));

		// The admin cannot set the limit below what is already in use, but can raise it.
		assertCode(put(422, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "ACTIVE", false, 1, List.of("sales"))), "LIMIT_BELOW_USAGE");
		put(200, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "ACTIVE", false, 0, List.of("sales")));
		callWithToken(201, owner, HttpMethod.POST, "/api/admin/users", userBody("STORE"));
		assertThat(get(ADMIN, CLIENTS + "/" + id + "/users").size()).isEqualTo(4);
	}

	@Test
	void theProductAdminRoleCannotBeGivenToAClientUser() {
		assertCode(post(422, Role.OWNER, "/api/admin/users", userBody("PLATFORM_ADMIN")), "ROLE_NOT_ALLOWED");
		assertCode(put(422, Role.OWNER, "/api/admin/permissions", Map.of("role", "PLATFORM_ADMIN", "module", "sales", "level", "FULL")), "ROLE_NOT_ALLOWED");
	}

	// ---- suspending ----

	@Test
	void aSuspendedClientIsLockedOutUntilReactivated() {
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(unique("susp")));
		long id = created.path("client").path("id").asLong();
		String email = created.path("email").asString();
		String owner = signIn(email, OWNER_PASSWORD);
		callWithToken(200, owner, HttpMethod.GET, "/api/masters/items", null);
		List<String> all = List.of("sales", "purchase", "inventory", "production", "quality", "reports");

		put(200, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "SUSPENDED", false, 0, all));
		// New sign-ins are refused, and so is the token issued before the suspension.
		assertCode(post(403, null, "/api/auth/login", Map.of("email", email, "password", OWNER_PASSWORD)), "TENANT_SUSPENDED");
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/masters/items", null), "TENANT_SUSPENDED");
		assertCode(callWithToken(403, owner, HttpMethod.GET, "/api/auth/me", null), "TENANT_SUSPENDED");
		assertCode(post(409, ADMIN, CLIENTS + "/" + id + "/impersonate", null), "TENANT_SUSPENDED");
		// Other clients carry on.
		get(Role.OWNER, "/api/masters/items");

		put(200, ADMIN, CLIENTS + "/" + id, updateBody("Acme Plastics", "ACTIVE", false, 0, all));
		callWithToken(200, owner, HttpMethod.GET, "/api/masters/items", null);
		signIn(email, OWNER_PASSWORD);
	}

	// ---- support sessions ----

	@Test
	void aSupportSessionActsAsTheClientUserAndIsRecorded() {
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(unique("help")));
		long id = created.path("client").path("id").asLong();
		String ownerEmail = created.path("email").asString();

		JsonNode session = post(200, ADMIN, CLIENTS + "/" + id + "/impersonate", null);
		assertThat(session.path("email").asString()).isEqualTo(ownerEmail);
		assertThat(session.path("role").asString()).isEqualTo("OWNER");
		assertThat(session.path("expiresInMinutes").asInt()).isEqualTo(30);
		String support = session.path("token").asString();

		JsonNode me = callWithToken(200, support, HttpMethod.GET, "/api/auth/me", null);
		assertThat(me.path("user").path("email").asString()).isEqualTo(ownerEmail);
		assertThat(me.path("impersonatedBy").asString()).isEqualTo(email(ADMIN));
		assertThat(me.path("client").path("slug").asString()).isEqualTo(created.path("client").path("slug").asString());

		// The admin works in the client's data, and the audit trail names both people.
		long item = callWithToken(201, support, HttpMethod.POST, "/api/masters/items", merge(itemBody("FINISHED"), "name", "Fixed by support")).path("id").asLong();
		JsonNode entry = callWithToken(200, support, HttpMethod.GET, "/api/admin/audit?entityType=Item&size=1", null).path("content").get(0);
		assertThat(entry.path("entityId").asLong()).isEqualTo(item);
		assertThat(entry.path("actor").asString()).isEqualTo(ownerEmail + " (support: " + email(ADMIN) + ")");
		// The client's own owner sees the record and who made it.
		String owner = signIn(ownerEmail, OWNER_PASSWORD);
		assertThat(callWithToken(200, owner, HttpMethod.GET, "/api/masters/items/" + item, null).path("createdBy").asString()).contains("support: " + email(ADMIN));
		// The product admin's own log records that the session was started.
		assertThat(get(ADMIN, "/api/platform/audit?size=5").toString()).contains("IMPERSONATE").contains(ownerEmail);
	}

	@Test
	void aSupportSessionHasLimits() {
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(unique("lim")));
		long id = created.path("client").path("id").asLong();
		String support = post(200, ADMIN, CLIENTS + "/" + id + "/impersonate", null).path("token").asString();

		// It cannot manage who signs in, reach the product area, or start another session.
		callWithToken(200, support, HttpMethod.GET, "/api/admin/users", null);
		assertCode(callWithToken(403, support, HttpMethod.POST, "/api/admin/users", userBody("STORE")), "IMPERSONATION_RESTRICTED");
		assertCode(callWithToken(403, support, HttpMethod.PUT, "/api/admin/permissions", Map.of("role", "CA", "module", "sales", "level", "FULL")), "IMPERSONATION_RESTRICTED");
		assertCode(callWithToken(403, support, HttpMethod.GET, CLIENTS, null), "FORBIDDEN");
		assertCode(callWithToken(403, support, HttpMethod.POST, CLIENTS + "/" + id + "/impersonate", null), "FORBIDDEN");
		// It is confined to that one client.
		assertThat(callWithToken(200, support, HttpMethod.GET, "/api/masters/items", null).path("totalElements").asInt()).isZero();
	}

	@Test
	void aSupportSessionCanBeStartedAsASpecificActiveUserOnly() {
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(unique("as")));
		long id = created.path("client").path("id").asLong();
		String owner = signIn(created.path("email").asString(), OWNER_PASSWORD);
		Map<String, Object> store = userBody("STORE");
		long storeId = callWithToken(201, owner, HttpMethod.POST, "/api/admin/users", store).path("id").asLong();

		// As the store keeper, the admin gets exactly the store keeper's rights.
		JsonNode session = post(200, ADMIN, CLIENTS + "/" + id + "/impersonate", Map.of("userId", storeId));
		assertThat(session.path("role").asString()).isEqualTo("STORE");
		String support = session.path("token").asString();
		callWithToken(200, support, HttpMethod.GET, "/api/inventory/stock", null);
		assertCode(callWithToken(403, support, HttpMethod.GET, "/api/settings/company", null), "FORBIDDEN");
		assertCode(callWithToken(403, support, HttpMethod.POST, "/api/masters/items", itemBody("FINISHED")), "FORBIDDEN");

		callWithToken(200, owner, HttpMethod.PUT, "/api/admin/users/" + storeId, merge(store, "active", false));
		assertCode(post(422, ADMIN, CLIENTS + "/" + id + "/impersonate", Map.of("userId", storeId)), "USER_INACTIVE");
		// A user of another client cannot be reached through this one.
		long demoOwner = get(Role.OWNER, "/api/admin/users?q=" + email(Role.OWNER)).path("content").get(0).path("id").asLong();
		assertCode(post(404, ADMIN, CLIENTS + "/" + id + "/impersonate", Map.of("userId", demoOwner)), "NOT_FOUND");
		assertCode(post(404, ADMIN, CLIENTS + "/99999999/impersonate", null), "NOT_FOUND");
	}

	@Test
	void theAdminCanResetAClientUsersPassword() {
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(unique("reset")));
		long id = created.path("client").path("id").asLong();
		String email = created.path("email").asString();
		long ownerId = get(ADMIN, CLIENTS + "/" + id + "/users").get(0).path("id").asLong();

		JsonNode reset = post(200, ADMIN, CLIENTS + "/" + id + "/users/" + ownerId + "/reset-password", null);
		assertThat(reset.path("email").asString()).isEqualTo(email);
		signIn(email, reset.path("temporaryPassword").asString());
		assertCode(post(401, null, "/api/auth/login", Map.of("email", email, "password", OWNER_PASSWORD)), "INVALID_CREDENTIALS");
		assertCode(post(404, ADMIN, CLIENTS + "/" + id + "/users/99999999/reset-password", null), "NOT_FOUND");
		assertCode(post(403, Role.OWNER, CLIENTS + "/" + id + "/users/" + ownerId + "/reset-password", null), "FORBIDDEN");
	}

	@Test
	void theAdminCanAddUsersForAClientInAnyClientRole() {
		String slug = unique("staff");
		JsonNode created = post(201, ADMIN, CLIENTS, merge(clientBody(slug), "maxUsers", 3));
		long id = created.path("client").path("id").asLong();
		String users = CLIENTS + "/" + id + "/users";
		String storeEmail = "store@" + slug + ".example";

		// With a password given, the new user signs in with it and works in their role only.
		post(201, ADMIN, users, Map.of("fullName", "Store Keeper", "email", storeEmail, "role", "STORE", "password", OWNER_PASSWORD));
		String store = signIn(storeEmail, OWNER_PASSWORD);
		callWithToken(200, store, HttpMethod.GET, "/api/inventory/stock", null);
		assertCode(callWithToken(403, store, HttpMethod.GET, "/api/admin/users", null), "FORBIDDEN");
		// Without one, a password is generated and shown once.
		JsonNode sales = post(201, ADMIN, users, Map.of("fullName", "Sales Person", "email", "sales@" + slug + ".example", "role", "SALES"));
		assertThat(sales.path("passwordGenerated").asBoolean()).isTrue();
		signIn(sales.path("email").asString(), sales.path("temporaryPassword").asString());
		assertThat(get(ADMIN, users).size()).isEqualTo(3);

		assertCode(post(422, ADMIN, users, Map.of("fullName", "One Too Many", "email", "extra@" + slug + ".example", "role", "QUALITY")), "USER_LIMIT_REACHED");
		assertCode(post(409, ADMIN, users, Map.of("fullName", "Again", "email", storeEmail, "role", "STORE")), "DUPLICATE_EMAIL");
		assertCode(post(422, ADMIN, users, Map.of("fullName", "Admin", "email", "boss@" + slug + ".example", "role", "PLATFORM_ADMIN")), "ROLE_NOT_ALLOWED");
		assertCode(post(400, ADMIN, users, Map.of("fullName", "", "email", "not-an-email", "role", "STORE")), "VALIDATION_FAILED");
		assertCode(post(403, Role.OWNER, users, Map.of("fullName", "X", "email", "x@" + slug + ".example", "role", "STORE")), "FORBIDDEN");
	}

	// ---- editing ----

	@Test
	void clientDetailsCanBeEditedButNotBrokenOrRenamedAtTheKey() {
		String slug = unique("edit");
		long id = post(201, ADMIN, CLIENTS, clientBody(slug)).path("client").path("id").asLong();

		Map<String, Object> change = updateBody("Acme Polymers Pvt Ltd", "ACTIVE", true, 25, List.of("sales", "inventory"));
		change.put("hosting", "DEDICATED");
		change.put("slug", "something-else");
		JsonNode updated = put(200, ADMIN, CLIENTS + "/" + id, change);
		assertThat(updated.path("name").asString()).isEqualTo("Acme Polymers Pvt Ltd");
		assertThat(updated.path("hosting").asString()).isEqualTo("DEDICATED");
		assertThat(updated.path("maxUsers").asInt()).isEqualTo(25);
		// The short name is the key on every row of the client's data, so it never changes.
		assertThat(updated.path("slug").asString()).isEqualTo(slug);
		assertThat(get(ADMIN, "/api/platform/audit?size=3").toString()).contains("Acme Polymers Pvt Ltd");

		assertCode(put(400, ADMIN, CLIENTS + "/" + id, updateBody("Acme", "ACTIVE", false, 0, List.of("sales", "payroll"))), "UNKNOWN_MODULE");
		JsonNode invalid = put(400, ADMIN, CLIENTS + "/" + id, obj("name", "", "maxUsers", -3, "modules", List.of()));
		assertThat(invalid.path("fieldErrors").propertyNames()).contains("name", "industry", "status", "hosting", "whiteLabel", "maxUsers", "modules");
		assertCode(put(404, ADMIN, CLIENTS + "/99999999", updateBody("Acme", "ACTIVE", false, 0, List.of("sales"))), "NOT_FOUND");
	}

	// ---- helpers ----

	private static Map<String, Object> clientBody(String slug) {
		return obj("name", "Acme Plastics", "slug", slug, "industry", "GENERAL", "stateCode", "27", "ownerName", "Asha Owner",
				"ownerEmail", slug + "@example.com", "ownerPassword", OWNER_PASSWORD);
	}

	private static Map<String, Object> updateBody(String name, String status, boolean whiteLabel, int maxUsers, List<String> modules) {
		return obj("name", name, "industry", "GENERAL", "status", status, "hosting", "SHARED", "whiteLabel", whiteLabel, "maxUsers", maxUsers, "modules", modules);
	}

	private static Map<String, Object> userBody(String role) {
		return obj("email", unique("user") + "@example.com", "fullName", "Team Member", "role", role, "password", "A-long-enough-password", "active", true);
	}

	/** Creates a client and returns its owner's token. */
	private String newClient(String slug) {
		JsonNode created = post(201, ADMIN, CLIENTS, clientBody(slug));
		return signIn(created.path("email").asString(), OWNER_PASSWORD);
	}

	private static Map<String, Object> merge(Map<String, Object> base, Object... overrides) {
		base.putAll(obj(overrides));
		return base;
	}
}
