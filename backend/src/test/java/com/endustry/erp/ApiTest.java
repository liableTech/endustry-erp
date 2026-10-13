package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.endustry.erp.platform.security.Role;
import com.endustry.erp.platform.validation.GstinValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the real application over HTTP with an in-memory database. Tests share one database, so
 * every fixture is created with a unique code and no test depends on another's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTest {

	private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);
	private static final Map<Role, String> TOKENS = new EnumMap<>(Role.class);

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected ObjectMapper json;

	@Value("${erp.seed.password}")
	protected String password;

	protected static final LocalDate TODAY = LocalDate.now();

	// ---- HTTP ----

	/** Calls the API as {@code role} (null for anonymous), asserts the status and returns the body. */
	protected JsonNode call(int expectedStatus, Role role, HttpMethod method, String path, Object body) {
		try {
			return callWithToken(expectedStatus, role == null ? null : token(role), method, path, body);
		} catch (Exception e) {
			throw new AssertionError(method + " " + path + " failed", e);
		}
	}

	/** As {@link #call}, for a token obtained in the test itself (a new client's owner, a support session). */
	protected JsonNode callWithToken(int expectedStatus, String token, HttpMethod method, String path, Object body) {
		try {
			MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
			if (token != null) {
				request.header("Authorization", "Bearer " + token);
			}
			if (body != null) {
				request.contentType(MediaType.APPLICATION_JSON)
						.content(body instanceof String raw ? raw : json.writeValueAsString(body));
			}
			MvcResult result = mvc.perform(request).andReturn();
			String content = result.getResponse().getContentAsString();
			assertThat(result.getResponse().getStatus()).as("%s %s -> %s", method, path, content).isEqualTo(expectedStatus);
			return content.isBlank() ? null : json.readTree(content);
		} catch (Exception e) {
			throw new AssertionError(method + " " + path + " failed", e);
		}
	}

	protected JsonNode get(Role role, String path) {
		return call(200, role, HttpMethod.GET, path, null);
	}

	protected JsonNode post(int status, Role role, String path, Object body) {
		return call(status, role, HttpMethod.POST, path, body);
	}

	protected JsonNode put(int status, Role role, String path, Object body) {
		return call(status, role, HttpMethod.PUT, path, body);
	}

	/** Asserts an error response carries the given machine-readable code. */
	protected static void assertCode(JsonNode error, String code) {
		assertThat(error.path("code").asString()).as(error.toString()).isEqualTo(code);
	}

	protected String token(Role role) throws Exception {
		String cached = TOKENS.get(role);
		if (cached != null) {
			return cached;
		}
		String content = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email(role), "password", password)))).andReturn()
				.getResponse().getContentAsString();
		String token = json.readTree(content).path("token").asString();
		TOKENS.put(role, token);
		return token;
	}

	protected static String email(Role role) {
		return role == Role.PLATFORM_ADMIN ? "admin@endustry.local" : role.name().toLowerCase() + "@endustry.local";
	}

	/** Signs in with an email and password and returns the token. */
	protected String signIn(String email, String secret) {
		return post(200, null, "/api/auth/login", Map.of("email", email, "password", secret)).path("token").asString();
	}

	// ---- fixtures ----

	protected static String unique(String prefix) {
		return prefix + SEQUENCE.incrementAndGet();
	}

	protected static Map<String, Object> obj(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	protected Map<String, Object> itemBody(String type) {
		return obj("code", unique("IT"), "name", "Test item", "type", type, "uom", "NOS", "hsnCode", "8483", "gstRate", 18,
				"purchasePrice", 100, "salePrice", 200);
	}

	protected long item(String type, Object... overrides) {
		Map<String, Object> body = itemBody(type);
		body.putAll(obj(overrides));
		return post(201, Role.OWNER, "/api/masters/items", body).path("id").asLong();
	}

	/** A valid GSTIN for the given state, different on every call. */
	protected static String gstin(String state) {
		int n = SEQUENCE.incrementAndGet();
		return GstinValidator.withCheckDigit(state + "AABCT" + n + "K" + "1Z");
	}

	protected Map<String, Object> partyBody(String type, String state) {
		return obj("code", unique("P"), "name", "Test party " + SEQUENCE.get(), "type", type, "stateCode", state,
				"gstin", gstin(state), "creditDays", 30, "creditLimit", 0);
	}

	protected long party(String type, String state, Object... overrides) {
		Map<String, Object> body = partyBody(type, state);
		body.putAll(obj(overrides));
		return post(201, Role.OWNER, "/api/masters/parties", body).path("id").asLong();
	}

	protected long warehouse() {
		return post(201, Role.OWNER, "/api/masters/warehouses", obj("code", unique("WH"), "name", "Test store")).path("id").asLong();
	}

	protected long workCentre(int hourlyCost) {
		return post(201, Role.OWNER, "/api/masters/work-centres",
				obj("code", unique("WC"), "name", "Test machine", "hourlyCost", hourlyCost, "capacityHoursPerDay", 8, "shifts", 1))
				.path("id").asLong();
	}

	/** Puts opening stock on the shelf. */
	protected void stockIn(long itemId, long warehouseId, double qty) {
		post(200, Role.OWNER, "/api/inventory/adjustments",
				obj("itemId", itemId, "warehouseId", warehouseId, "qtyChange", qty, "reasonCode", "OPENING"));
	}

	/** Available quantity of an item in a warehouse, summed over batches. */
	protected BigDecimal available(long itemId, long warehouseId) {
		return stockOf(itemId, warehouseId, "AVAILABLE");
	}

	protected BigDecimal stockOf(long itemId, long warehouseId, String status) {
		BigDecimal total = BigDecimal.ZERO;
		for (JsonNode row : get(Role.OWNER, "/api/inventory/stock?warehouseId=" + warehouseId)) {
			if (row.path("itemId").asLong() == itemId && row.path("status").asString().equals(status)) {
				total = total.add(row.path("qty").decimalValue());
			}
		}
		return total;
	}

	protected static Map<String, Object> line(long itemId, double qty, double rate) {
		return obj("itemId", itemId, "qty", qty, "rate", rate);
	}

	@SafeVarargs
	protected final Map<String, Object> docBody(long partyId, Long warehouseId, Map<String, Object>... lines) {
		return obj("partyId", partyId, "docDate", TODAY.toString(), "warehouseId", warehouseId, "lines", new ArrayList<>(List.of(lines)));
	}

	protected JsonNode act(int status, Role role, String docPath, long id, String action) {
		return post(status, role, docPath + "/" + id + "/actions/" + action, null);
	}

	/** Rebuilds an editable request body from a document returned by the API. */
	protected Map<String, Object> bodyOf(JsonNode doc) {
		List<Map<String, Object>> lines = new ArrayList<>();
		for (JsonNode l : doc.path("lines")) {
			lines.add(obj("itemId", l.path("item").path("id").asLong(), "qty", l.path("qty").decimalValue(),
					"rate", l.path("rate").decimalValue(), "discountPct", l.path("discountPct").decimalValue(),
					"sourceLineId", l.path("sourceLineId").isNull() ? null : l.path("sourceLineId").asLong()));
		}
		return obj("partyId", doc.path("party").path("id").asLong(), "docDate", doc.path("docDate").asString(),
				"warehouseId", doc.path("warehouse").isNull() ? null : doc.path("warehouse").path("id").asLong(),
				"referenceNo", doc.path("referenceNo").isNull() ? null : doc.path("referenceNo").asString(), "lines", lines);
	}

	protected static void assertAmount(JsonNode value, String expected) {
		assertThat(value.decimalValue()).isEqualByComparingTo(expected);
	}
}
