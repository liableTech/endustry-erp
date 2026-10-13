package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** The label service: each client's own words for customers, items, machines and the rest. */
class LabelsTest extends ApiTest {

	private static final String LABELS = "/api/settings/labels";
	private static final String OWNER_PASSWORD = "First-Login-Pass-9";

	@Test
	void anOwnerRenamesATermAndEveryoneInTheCompanySeesIt() {
		assertThat(get(Role.SALES, "/api/auth/me").path("labels").path("customer").path("plural").asString()).isEqualTo("Customers");

		JsonNode row = put(200, Role.OWNER, LABELS + "/customer", Map.of("singular", " Client ", "plural", "Clients"));
		assertThat(row.path("current").path("singular").asString()).isEqualTo("Client");
		assertThat(row.path("renamed").asBoolean()).isTrue();
		try {
			// Sent with every session, so even a role that cannot open settings uses the new word.
			assertThat(get(Role.SALES, "/api/auth/me").path("labels").path("customer").path("plural").asString()).isEqualTo("Clients");
			assertCode(put(403, Role.SALES, LABELS + "/customer", Map.of("singular", "Buyer", "plural", "Buyers")), "FORBIDDEN");
		} finally {
			JsonNode reset = call(200, Role.OWNER, HttpMethod.DELETE, LABELS + "/customer", null);
			assertThat(reset.path("renamed").asBoolean()).isFalse();
		}
		assertThat(get(Role.OWNER, "/api/auth/me").path("labels").path("customer").path("singular").asString()).isEqualTo("Customer");
	}

	@Test
	void aNewClientStartsWithItsIndustrysWordsAndRenamesStayWithinIt() {
		Map<String, Object> client = obj("name", "Bright Consulting", "slug", unique("svc"), "industry", "SERVICES", "stateCode", "27",
				"ownerName", "Asha Owner", "ownerEmail", unique("asha") + "@example.com", "ownerPassword", OWNER_PASSWORD);
		JsonNode created = post(201, Role.PLATFORM_ADMIN, "/api/platform/clients", client);
		JsonNode session = post(200, null, "/api/auth/login", Map.of("email", created.path("email").asString(), "password", OWNER_PASSWORD));
		assertThat(session.path("labels").path("customer").path("plural").asString()).isEqualTo("Clients");
		assertThat(session.path("labels").path("quotation").path("singular").asString()).isEqualTo("Proposal");
		assertThat(session.path("labels").path("salesOrder").path("singular").asString()).isEqualTo("Sales order");

		String owner = session.path("token").asString();
		callWithToken(200, owner, HttpMethod.PUT, LABELS + "/item", Map.of("singular", "Package", "plural", "Packages"));
		JsonNode rows = callWithToken(200, owner, HttpMethod.GET, LABELS, null);
		assertThat(rows.toString()).contains("\"Packages\"");
		// Another company keeps its own words.
		assertThat(get(Role.OWNER, "/api/auth/me").path("labels").path("item").path("plural").asString()).isEqualTo("Items");
	}

	@Test
	void aLabelMustBeGivenAndKnown() {
		assertCode(put(400, Role.OWNER, LABELS + "/customer", Map.of("singular", " ", "plural", "Clients")), "INVALID_LABEL");
		assertCode(put(400, Role.OWNER, LABELS + "/customer", Map.of("singular", "C".repeat(41), "plural", "Clients")), "INVALID_LABEL");
		assertCode(put(404, Role.OWNER, LABELS + "/spaceship", Map.of("singular", "Ship", "plural", "Ships")), "NOT_FOUND");
	}
}
