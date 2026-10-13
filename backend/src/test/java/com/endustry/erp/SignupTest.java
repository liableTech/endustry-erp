package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** Self sign-up: closed until a product admin opens it, with approval by hand or automatic. */
class SignupTest extends ApiTest {

	private static final String PUBLIC = "/api/public/signup";
	private static final String SETTINGS = "/api/platform/signup-settings";
	private static final String SIGNUPS = "/api/platform/signups";
	private static final String COUPONS = "/api/platform/coupons";
	private static final String CHOSEN_PASSWORD = "Chosen-By-Owner-7";

	@AfterEach
	void closeSignUp() {
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(false, false, 0));
	}

	@Test
	void signUpIsClosedUntilAProductAdminOpensIt() {
		JsonNode options = call(200, null, HttpMethod.GET, PUBLIC, null);
		assertThat(options.path("enabled").asBoolean()).isFalse();
		assertThat(options.path("plans").toString()).contains("STARTER", "GROWTH", "BUSINESS");
		assertThat(options.path("industries").toString()).contains("AGRICULTURE", "TRADING");
		assertCode(post(403, null, PUBLIC, form(unique("closed"), "STARTER", "TRADING")), "SIGNUP_CLOSED");

		assertCode(put(403, Role.OWNER, SETTINGS, settings(true, true, 0)), "FORBIDDEN");
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(true, false, 0));
		assertThat(call(200, null, HttpMethod.GET, PUBLIC, null).path("enabled").asBoolean()).isTrue();
	}

	@Test
	void aSignUpWaitsForApprovalAndThenBecomesAClientOnItsPlan() {
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(true, false, 0));
		String name = unique("wait");
		Map<String, Object> form = form(name, "STARTER", "ENGINEERING");
		String email = (String) form.get("ownerEmail");

		JsonNode receipt = post(201, null, PUBLIC, form);
		assertThat(receipt.path("status").asString()).isEqualTo("PENDING");
		assertThat(receipt.path("reference").asString()).startsWith("DEMO-");
		assertThat(receipt.path("amount").asInt()).isEqualTo(999);
		// No workspace yet, and the same email cannot queue twice.
		post(401, null, "/api/auth/login", Map.of("email", email, "password", CHOSEN_PASSWORD));
		assertCode(post(409, null, PUBLIC, form), "DUPLICATE_EMAIL");

		JsonNode request = pending(email);
		assertThat(request.path("paymentStatus").asString()).isEqualTo("DEMO_PAID");
		assertThat(request.toString()).doesNotContain("passwordHash");
		assertCode(post(403, Role.OWNER, SIGNUPS + "/" + request.path("id").asLong() + "/approve", null), "FORBIDDEN");
		JsonNode approved = post(200, Role.PLATFORM_ADMIN, SIGNUPS + "/" + request.path("id").asLong() + "/approve", null);
		assertThat(approved.path("status").asString()).isEqualTo("APPROVED");
		assertCode(post(409, Role.PLATFORM_ADMIN, SIGNUPS + "/" + request.path("id").asLong() + "/approve", null), "ALREADY_DECIDED");

		// The Owner signs in with the password they chose, and gets what the Starter plan includes.
		JsonNode session = post(200, null, "/api/auth/login", Map.of("email", email, "password", CHOSEN_PASSWORD));
		assertThat(session.toString()).contains("\"OWNER\"");
		JsonNode client = client(approved.path("tenantSlug").asString());
		assertThat(client.path("plan").asString()).isEqualTo("STARTER");
		assertThat(client.path("maxUsers").asInt()).isEqualTo(3);
		assertThat(client.path("modules").toString()).contains("sales", "inventory").doesNotContain("production", "quality");
		assertThat(client.path("paidUntil").asString()).isNotBlank();
	}

	@Test
	void withAutomaticApprovalTheWorkspaceIsReadyAtOnce() {
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(true, true, 0));
		Map<String, Object> form = form(unique("auto"), "GROWTH", "SERVICES");
		String email = (String) form.get("ownerEmail");

		JsonNode receipt = post(201, null, PUBLIC, form);
		assertThat(receipt.path("status").asString()).isEqualTo("APPROVED");
		signIn(email, CHOSEN_PASSWORD);
		JsonNode request = request(email);
		assertThat(request.path("decidedBy").asString()).isEqualTo("automatic approval");
		// A consultancy on a full plan still gets only the modules its industry uses.
		JsonNode client = client(request.path("tenantSlug").asString());
		assertThat(client.path("modules").toString()).contains("sales", "reports").doesNotContain("inventory", "production");
		assertThat(client.path("maxUsers").asInt()).isEqualTo(10);
		assertCode(post(409, null, PUBLIC, form), "DUPLICATE_EMAIL");
	}

	@Test
	void aRejectedSignUpNeverBecomesAClientAndBadFormsAreRefused() {
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(true, false, 0));
		Map<String, Object> form = form(unique("no"), "BUSINESS", "RETAIL");
		String email = (String) form.get("ownerEmail");
		post(201, null, PUBLIC, form);
		long id = pending(email).path("id").asLong();

		JsonNode rejected = post(200, Role.PLATFORM_ADMIN, SIGNUPS + "/" + id + "/reject", Map.of("note", "Outside our service area"));
		assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
		assertThat(rejected.path("note").asString()).isEqualTo("Outside our service area");
		assertCode(post(409, Role.PLATFORM_ADMIN, SIGNUPS + "/" + id + "/approve", null), "ALREADY_DECIDED");
		post(401, null, "/api/auth/login", Map.of("email", email, "password", CHOSEN_PASSWORD));

		Map<String, Object> bad = form(unique("bad"), "STARTER", "TRADING");
		bad.put("ownerEmail", "not-an-email");
		bad.put("password", "short");
		bad.put("companyName", " ");
		JsonNode invalid = post(400, null, PUBLIC, bad);
		assertThat(invalid.path("fieldErrors").propertyNames()).contains("ownerEmail", "password", "companyName");
	}

	@Test
	void freeMembershipChargesNothingAndRunsForTheMonthsTheAdminChose() {
		assertCode(put(400, Role.PLATFORM_ADMIN, SETTINGS, settings(true, true, 4)), "INVALID_FREE_MONTHS");
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(true, true, 2));
		assertThat(call(200, null, HttpMethod.GET, PUBLIC, null).path("freeMonths").asInt()).isEqualTo(2);
		Map<String, Object> form = form(unique("free"), "GROWTH", "ENGINEERING");
		String email = (String) form.get("ownerEmail");

		JsonNode receipt = post(201, null, PUBLIC, form);
		assertThat(receipt.path("amount").asInt()).isZero();
		assertThat(receipt.path("freeMonths").asInt()).isEqualTo(2);
		assertThat(receipt.path("reference").asString()).startsWith("FREE-");
		JsonNode request = request(email);
		assertThat(request.path("paymentStatus").asString()).isEqualTo("FREE_MEMBERSHIP");
		signIn(email, CHOSEN_PASSWORD);
		assertThat(client(request.path("tenantSlug").asString()).path("paidUntil").asString()).isEqualTo(TODAY.plusMonths(2).toString());
	}

	@Test
	void aCouponTakesItsPercentageOffAndStopsWhenUsedUpOrSwitchedOff() {
		put(200, Role.PLATFORM_ADMIN, SETTINGS, settings(true, false, 0));
		String code = unique("SAVE").toUpperCase();
		assertCode(post(403, Role.OWNER, COUPONS, obj("code", code, "percentOff", 20)), "FORBIDDEN");
		JsonNode coupon = post(201, Role.PLATFORM_ADMIN, COUPONS, obj("code", code.toLowerCase(), "percentOff", 20, "maxUses", 1, "note", "Trade fair"));
		assertThat(coupon.path("code").asString()).isEqualTo(code);
		assertCode(post(409, Role.PLATFORM_ADMIN, COUPONS, obj("code", code, "percentOff", 10)), "DUPLICATE");
		// Left blank, a code is generated.
		assertThat(post(201, Role.PLATFORM_ADMIN, COUPONS, obj("percentOff", 5)).path("code").asString()).hasSize(8);
		assertThat(post(400, Role.PLATFORM_ADMIN, COUPONS, obj("percentOff", 120)).path("fieldErrors").propertyNames()).contains("percentOff");

		JsonNode quote = call(200, null, HttpMethod.GET, PUBLIC + "/coupon?plan=GROWTH&code=" + code.toLowerCase(), null);
		assertThat(quote.path("percentOff").asInt()).isEqualTo(20);
		assertThat(quote.path("monthlyPrice").asDouble()).isEqualTo(1999.2);
		assertCode(call(422, null, HttpMethod.GET, PUBLIC + "/coupon?plan=GROWTH&code=NOSUCHCODE", null), "COUPON_INVALID");

		Map<String, Object> form = form(unique("cpn"), "GROWTH", "ENGINEERING");
		form.put("couponCode", code);
		JsonNode receipt = post(201, null, PUBLIC, form);
		assertThat(receipt.path("amount").asDouble()).isEqualTo(1999.2);
		assertThat(receipt.path("discount").asDouble()).isEqualTo(499.8);
		assertThat(request((String) form.get("ownerEmail")).path("couponCode").asString()).isEqualTo(code);

		// One use only: the next business is told, and nothing is created for it.
		Map<String, Object> second = form(unique("cpn"), "GROWTH", "ENGINEERING");
		second.put("couponCode", code);
		assertCode(post(422, null, PUBLIC, second), "COUPON_USED_UP");
		// The admin raises the limit, changes the discount and renames the code; the sign-up that used it follows.
		assertCode(put(403, Role.OWNER, COUPONS + "/" + code, obj("percentOff", 30)), "FORBIDDEN");
		String renamed = code + "X";
		JsonNode edited = put(200, Role.PLATFORM_ADMIN, COUPONS + "/" + code, obj("code", renamed, "percentOff", 30, "maxUses", 5, "note", "Raised"));
		assertThat(edited.path("code").asString()).isEqualTo(renamed);
		assertThat(edited.path("maxUses").asInt()).isEqualTo(5);
		assertThat(edited.path("used").asInt()).isEqualTo(1);
		assertThat(request((String) form.get("ownerEmail")).path("couponCode").asString()).isEqualTo(renamed);
		assertCode(call(422, null, HttpMethod.GET, PUBLIC + "/coupon?plan=GROWTH&code=" + code, null), "COUPON_INVALID");
		assertThat(call(200, null, HttpMethod.GET, PUBLIC + "/coupon?plan=GROWTH&code=" + renamed, null).path("percentOff").asInt()).isEqualTo(30);
		assertCode(put(404, Role.PLATFORM_ADMIN, COUPONS + "/NOSUCHCODE", obj("percentOff", 30)), "NOT_FOUND");
		put(200, Role.PLATFORM_ADMIN, COUPONS + "/" + renamed, obj("code", code, "percentOff", 20, "maxUses", 1));
		// Rejecting the first sign-up gives the use back; switching the coupon off stops it.
		post(200, Role.PLATFORM_ADMIN, SIGNUPS + "/" + pending((String) form.get("ownerEmail")).path("id").asLong() + "/reject", null);
		call(200, null, HttpMethod.GET, PUBLIC + "/coupon?plan=STARTER&code=" + code, null);
		put(200, Role.PLATFORM_ADMIN, COUPONS + "/" + code + "/active", Map.of("active", false));
		assertCode(post(422, null, PUBLIC, second), "COUPON_INVALID");
	}

	private JsonNode pending(String email) {
		JsonNode request = request(email);
		assertThat(request.path("status").asString()).isEqualTo("PENDING");
		return request;
	}

	private JsonNode request(String email) {
		for (JsonNode request : get(Role.PLATFORM_ADMIN, SIGNUPS).path("requests")) {
			if (request.path("ownerEmail").asString().equals(email)) {
				return request;
			}
		}
		throw new AssertionError("No sign-up for " + email);
	}

	private JsonNode client(String slug) {
		for (JsonNode row : get(Role.PLATFORM_ADMIN, "/api/platform/clients")) {
			if (row.path("client").path("slug").asString().equals(slug)) {
				return row.path("client");
			}
		}
		throw new AssertionError("No client " + slug);
	}

	private static Map<String, Object> settings(boolean enabled, boolean autoApprove, int freeMonths) {
		return Map.of("enabled", enabled, "autoApprove", autoApprove, "freeMonths", freeMonths);
	}

	private Map<String, Object> form(String name, String plan, String industry) {
		return obj("companyName", "Signup " + name + " Pvt Ltd", "industry", industry, "stateCode", "27", "ownerName", "Asha Owner",
				"ownerEmail", name + "@example.com", "phone", "9876543210", "password", CHOSEN_PASSWORD, "plan", plan);
	}
}
