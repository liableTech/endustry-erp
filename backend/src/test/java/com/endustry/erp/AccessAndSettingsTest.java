package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

class AccessAndSettingsTest extends ApiTest {

	// ---- sign-in ----

	@Test
	void signInReturnsTokenUserAndPermissions() {
		JsonNode session = post(200, null, "/api/auth/login", Map.of("email", email(Role.SALES), "password", password));
		assertThat(session.path("token").asString()).isNotBlank();
		assertThat(session.path("user").path("role").asString()).isEqualTo("SALES");
		assertThat(session.path("user").has("passwordHash")).isFalse();
		assertThat(session.path("permissions").path("sales").asString()).isEqualTo("FULL");
		assertThat(session.path("permissions").path("admin").asString()).isEqualTo("NONE");
	}

	@Test
	void wrongPasswordAndUnknownUserGetTheSameAnswer() {
		assertCode(post(401, null, "/api/auth/login", Map.of("email", email(Role.OWNER), "password", "not-the-password")), "INVALID_CREDENTIALS");
		assertCode(post(401, null, "/api/auth/login", Map.of("email", "nobody@endustry.local", "password", password)), "INVALID_CREDENTIALS");
	}

	@Test
	void signInValidatesItsInput() {
		JsonNode error = post(400, null, "/api/auth/login", Map.of("email", "not-an-email", "password", ""));
		assertCode(error, "VALIDATION_FAILED");
		assertThat(error.path("fieldErrors").has("email")).isTrue();
		assertThat(error.path("fieldErrors").has("password")).isTrue();
	}

	@Test
	void requestsWithoutOrWithABadTokenAreRejected() throws Exception {
		assertCode(call(401, null, HttpMethod.GET, "/api/masters/items", null), "UNAUTHENTICATED");
		int status = mvc.perform(MockMvcRequestBuilders.get("/api/masters/items").header("Authorization", "Bearer made.up.token")
				.accept(MediaType.APPLICATION_JSON)).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(401);
	}

	// ---- role permissions ----

	@Test
	void aRoleCanReadWhatItMayViewButNotChangeIt() {
		get(Role.STORE, "/api/masters/items");
		assertCode(post(403, Role.STORE, "/api/masters/items", itemBody("RAW_MATERIAL")), "FORBIDDEN");
	}

	@Test
	void aRoleCannotTouchAModuleItHasNoAccessTo() {
		long vendor = party("VENDOR", "27");
		long item = item("RAW_MATERIAL");
		assertCode(post(403, Role.SALES, "/api/purchase/docs/purchase-order", docBody(vendor, null, line(item, 1, 10))), "FORBIDDEN");
		assertCode(call(403, Role.SALES, HttpMethod.GET, "/api/purchase/docs/purchase-order", null), "FORBIDDEN");
		assertCode(call(403, Role.ACCOUNTS, HttpMethod.GET, "/api/settings/company", null), "FORBIDDEN");
		assertCode(call(403, Role.CA, HttpMethod.GET, "/api/admin/users", null), "FORBIDDEN");
	}

	@Test
	void theOwnerCanChangeTheMatrixAndItTakesEffectImmediately() {
		assertCode(call(403, Role.CA, HttpMethod.GET, "/api/quality/inspections", null), "FORBIDDEN");
		put(200, Role.OWNER, "/api/admin/permissions", Map.of("role", "CA", "module", "quality", "level", "VIEW"));
		get(Role.CA, "/api/quality/inspections");
		put(200, Role.OWNER, "/api/admin/permissions", Map.of("role", "CA", "module", "quality", "level", "NONE"));
		assertCode(call(403, Role.CA, HttpMethod.GET, "/api/quality/inspections", null), "FORBIDDEN");
	}

	@Test
	void theOwnerCannotBeLockedOut() {
		assertCode(put(422, Role.OWNER, "/api/admin/permissions", Map.of("role", "OWNER", "module", "admin", "level", "VIEW")), "OWNER_LOCKOUT");
		assertCode(put(400, Role.OWNER, "/api/admin/permissions", Map.of("role", "OWNER", "module", "payroll", "level", "FULL")), "UNKNOWN_MODULE");
	}

	// ---- users ----

	@Test
	void usersAreCreatedWithAPasswordAndNeverDeleted() {
		String email = unique("user") + "@endustry.local";
		assertCode(post(400, Role.OWNER, "/api/admin/users", obj("email", email, "fullName", "New User", "role", "STORE")), "PASSWORD_REQUIRED");
		JsonNode weak = post(400, Role.OWNER, "/api/admin/users", obj("email", email, "fullName", "New User", "role", "STORE", "password", "short"));
		assertThat(weak.path("fieldErrors").has("password")).isTrue();

		JsonNode created = post(201, Role.OWNER, "/api/admin/users",
				obj("email", email, "fullName", "New User", "role", "STORE", "password", "A-long-enough-password"));
		assertThat(created.has("password")).isFalse();
		post(200, null, "/api/auth/login", Map.of("email", email, "password", "A-long-enough-password"));
		assertCode(post(409, Role.OWNER, "/api/admin/users",
				obj("email", email.toUpperCase(), "fullName", "Twin", "role", "STORE", "password", "A-long-enough-password")), "DUPLICATE");
		assertCode(call(422, Role.OWNER, HttpMethod.DELETE, "/api/admin/users/" + created.path("id").asLong(), null), "USER_DELETE_BLOCKED");

		// A deactivated user can no longer sign in.
		put(200, Role.OWNER, "/api/admin/users/" + created.path("id").asLong(),
				obj("email", email, "fullName", "New User", "role", "STORE", "active", false));
		assertCode(post(401, null, "/api/auth/login", Map.of("email", email, "password", "A-long-enough-password")), "INVALID_CREDENTIALS");
	}

	@Test
	void theLastOwnerCannotBeDemoted() {
		JsonNode owner = get(Role.OWNER, "/api/admin/users?q=" + email(Role.OWNER)).path("content").get(0);
		assertCode(put(422, Role.OWNER, "/api/admin/users/" + owner.path("id").asLong(),
				obj("email", email(Role.OWNER), "fullName", "Owner", "role", "SALES", "active", true)), "LAST_OWNER");
	}

	// ---- theme, company, parameters, numbering ----

	@Test
	void themeChangesAreSavedAndServedToTheSignInPage() {
		JsonNode theme = get(Role.OWNER, "/api/settings/theme");
		Map<String, Object> change = obj("appName", "Factory One", "mode", "dark", "primaryColor", "#0F766E", "accentColor", "#F59E0B",
				"successColor", "#16A34A", "warningColor", "#D97706", "dangerColor", "#DC2626", "sidebarStyle", "brand",
				"radius", 8, "density", "compact", "fontFamily", "Poppins");
		put(200, Role.OWNER, "/api/settings/theme", change);
		// A white-label client's own sign-in link carries its branding...
		JsonNode branding = get(null, "/api/public/branding?client=demo");
		assertThat(branding.path("theme").path("primaryColor").asString()).isEqualTo("#0F766E");
		assertThat(branding.path("theme").path("mode").asString()).isEqualTo("dark");
		assertThat(branding.path("companyName").asString()).isEqualTo("Shree Precision");
		// ...its signed-in users see it too...
		assertThat(get(Role.SALES, "/api/auth/branding").path("theme").path("primaryColor").asString()).isEqualTo("#0F766E");
		// ...while the plain sign-in page, and any unknown link, keep the product's look.
		assertThat(get(null, "/api/public/branding").path("theme").path("primaryColor").asString()).isNotEqualTo("#0F766E");
		assertThat(get(null, "/api/public/branding?client=no-such-client").path("companyName").asString()).isEqualTo("Endustry ERP");

		// Put the original back so other tests see defaults.
		put(200, Role.OWNER, "/api/settings/theme", json.convertValue(theme, Map.class));
	}

	@Test
	void themeRejectsValuesTheScreenCannotRender() {
		JsonNode error = put(400, Role.OWNER, "/api/settings/theme", obj("appName", "", "mode", "neon", "primaryColor", "blue",
				"accentColor", "#12345", "successColor", "#16A34A", "warningColor", "#D97706", "dangerColor", "#DC2626",
				"sidebarStyle", "dark", "radius", 99, "density", "comfortable", "fontFamily", "Comic Sans"));
		assertCode(error, "VALIDATION_FAILED");
		assertThat(error.path("fieldErrors").propertyNames())
				.contains("appName", "mode", "primaryColor", "accentColor", "radius", "fontFamily");
	}

	@Test
	void companyGstinMustAgreeWithStateAndPan() {
		JsonNode company = get(Role.OWNER, "/api/settings/company");
		@SuppressWarnings("unchecked")
		Map<String, Object> body = json.convertValue(company, Map.class);
		body.put("stateCode", "24");
		assertCode(put(422, Role.OWNER, "/api/settings/company", body), "GSTIN_STATE_MISMATCH");
		body.put("stateCode", "27");
		body.put("pan", "ZZZZZ9999Z");
		assertCode(put(422, Role.OWNER, "/api/settings/company", body), "GSTIN_PAN_MISMATCH");
		body.put("pan", company.path("pan").asString());
		body.put("gstin", "27AAKCS4821P1Z0");
		assertThat(put(400, Role.OWNER, "/api/settings/company", body).path("fieldErrors").has("gstin")).isTrue();
	}

	@Test
	void parametersAcceptOnlyValuesOfTheirType() {
		JsonNode parameters = get(Role.OWNER, "/api/settings/parameters");
		long limit = find(parameters, "purchase.approvalLimit");
		long mode = find(parameters, "sales.creditCheckMode");
		long negative = find(parameters, "inventory.allowNegativeStock");
		assertCode(put(400, Role.OWNER, "/api/settings/parameters/" + limit, Map.of("value", "lots")), "INVALID_PARAMETER");
		assertCode(put(400, Role.OWNER, "/api/settings/parameters/" + limit, Map.of("value", "-5")), "INVALID_PARAMETER");
		assertCode(put(400, Role.OWNER, "/api/settings/parameters/" + mode, Map.of("value", "MAYBE")), "INVALID_PARAMETER");
		assertCode(put(400, Role.OWNER, "/api/settings/parameters/" + negative, Map.of("value", "yes")), "INVALID_PARAMETER");
		assertThat(put(200, Role.OWNER, "/api/settings/parameters/" + limit, Map.of("value", "500000")).path("paramValue").asString())
				.isEqualTo("500000");
		assertCode(put(404, Role.OWNER, "/api/settings/parameters/999999", Map.of("value", "1")), "NOT_FOUND");
	}

	@Test
	void numberSeriesCannotRewindOrOutgrowSixteenCharacters() {
		JsonNode series = null;
		for (JsonNode s : get(Role.OWNER, "/api/settings/number-series")) {
			if (s.path("seriesKey").asString().equals("QUOTATION")) {
				series = s;
			}
		}
		String path = "/api/settings/number-series/" + series.path("id").asLong();
		long next = series.path("nextNumber").asLong();

		put(200, Role.OWNER, path, seriesBody("QT", 4, next + 50));
		assertCode(put(422, Role.OWNER, path, seriesBody("QT", 4, next)), "SERIES_REWIND");
		// QUOTE/26-27/000123 would be 18 characters; GST allows 16.
		assertCode(put(422, Role.OWNER, path, seriesBody("QUOTE", 6, next + 50)), "DOC_NO_TOO_LONG");
		assertThat(put(400, Role.OWNER, path, seriesBody("qt-1", 4, next + 50)).path("fieldErrors").has("prefix")).isTrue();
	}

	private static Map<String, Object> seriesBody(String prefix, int padding, long nextNumber) {
		return obj("seriesKey", "QUOTATION", "prefix", prefix, "includeFinancialYear", true, "padding", padding, "nextNumber", nextNumber);
	}

	private static long find(JsonNode parameters, String key) {
		for (JsonNode p : parameters) {
			if (p.path("paramKey").asString().equals(key)) {
				return p.path("id").asLong();
			}
		}
		throw new AssertionError("No parameter " + key);
	}
}
