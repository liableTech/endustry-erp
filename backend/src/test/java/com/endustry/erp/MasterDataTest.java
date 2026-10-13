package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.endustry.erp.platform.security.Role;
import com.endustry.erp.platform.validation.GstinValidator;

import tools.jackson.databind.JsonNode;

class MasterDataTest extends ApiTest {

	// ---- items ----

	@Test
	void createsAnItemAndNormalisesItsCode() {
		Map<String, Object> body = itemBody("FINISHED");
		body.put("code", "shaft-" + unique("x"));
		JsonNode created = post(201, Role.OWNER, "/api/masters/items", body);
		assertThat(created.path("code").asString()).isEqualTo(((String) body.get("code")).toUpperCase());
		assertThat(created.path("active").asBoolean()).isTrue();
		assertAmount(created.path("avgCost"), "0");
		assertThat(get(Role.STORE, "/api/masters/items/" + created.path("id").asLong()).path("name").asString()).isEqualTo("Test item");
	}

	@Test
	void rejectsAnItemWithMissingOrOutOfRangeFields() {
		JsonNode error = post(400, Role.OWNER, "/api/masters/items",
				obj("code", "x", "name", "", "uom", "", "gstRate", 55, "hsnCode", "84", "purchasePrice", -1, "leadTimeDays", -3));
		assertCode(error, "VALIDATION_FAILED");
		assertThat(error.path("fieldErrors").propertyNames())
				.contains("code", "name", "type", "uom", "gstRate", "hsnCode", "purchasePrice", "leadTimeDays");
	}

	@Test
	void rejectsADuplicateItemCodeAndAnUnknownUnit() {
		Map<String, Object> body = itemBody("RAW_MATERIAL");
		post(201, Role.OWNER, "/api/masters/items", body);
		assertCode(post(409, Role.OWNER, "/api/masters/items", body), "DUPLICATE");

		Map<String, Object> badUnit = itemBody("RAW_MATERIAL");
		badUnit.put("uom", "BUSHEL");
		assertCode(post(422, Role.OWNER, "/api/masters/items", badUnit), "UNKNOWN_UOM");
	}

	@Test
	void aServiceItemCannotBeBatchTracked() {
		Map<String, Object> body = itemBody("SERVICE");
		body.put("batchTracked", true);
		assertCode(post(422, Role.OWNER, "/api/masters/items", body), "SERVICE_NOT_STOCKED");
	}

	@Test
	void rejectsMalformedJsonAndWrongTypes() {
		assertCode(post(400, Role.OWNER, "/api/masters/items", "{ not json"), "MALFORMED_REQUEST");
		Map<String, Object> body = itemBody("RAW_MATERIAL");
		body.put("type", "WIDGET");
		assertCode(post(400, Role.OWNER, "/api/masters/items", body), "MALFORMED_REQUEST");
		assertCode(call(400, Role.OWNER, HttpMethod.GET, "/api/masters/items/abc", null), "MALFORMED_REQUEST");
		assertCode(call(404, Role.OWNER, HttpMethod.GET, "/api/masters/items/99999999", null), "NOT_FOUND");
	}

	@Test
	void anUpdateOfAStaleCopyIsRefused() {
		Map<String, Object> body = itemBody("RAW_MATERIAL");
		JsonNode created = post(201, Role.OWNER, "/api/masters/items", body);
		String path = "/api/masters/items/" + created.path("id").asLong();
		body.put("version", created.path("version").asLong());
		body.put("name", "Renamed once");
		put(200, Role.OWNER, path, body);
		body.put("name", "Renamed from the stale copy");
		assertCode(put(409, Role.OWNER, path, body), "STALE_RECORD");
	}

	@Test
	void theClientCannotSetTheSystemMaintainedAverageCost() {
		Map<String, Object> body = itemBody("RAW_MATERIAL");
		body.put("avgCost", 9999);
		JsonNode created = post(201, Role.OWNER, "/api/masters/items", body);
		assertAmount(created.path("avgCost"), "0");
		assertAmount(put(200, Role.OWNER, "/api/masters/items/" + created.path("id").asLong(), body).path("avgCost"), "0");
	}

	@Test
	void anItemInUseCannotBeDeletedButAnUnusedOneCan() {
		long unused = item("RAW_MATERIAL");
		call(204, Role.OWNER, HttpMethod.DELETE, "/api/masters/items/" + unused, null);
		assertCode(call(404, Role.OWNER, HttpMethod.GET, "/api/masters/items/" + unused, null), "NOT_FOUND");

		long used = item("FINISHED");
		post(201, Role.OWNER, "/api/sales/docs/quotation", docBody(party("CUSTOMER", "27"), null, line(used, 1, 100)));
		assertCode(call(409, Role.OWNER, HttpMethod.DELETE, "/api/masters/items/" + used, null), "REFERENCE_CONFLICT");
	}

	@Test
	void listsCanBeSearchedFilteredAndPaged() {
		String tag = unique("Zebra");
		item("RAW_MATERIAL", "name", tag + " bar");
		item("FINISHED", "name", tag + " gear");
		item("FINISHED", "name", tag + " shaft", "active", false);

		assertThat(get(Role.OWNER, "/api/masters/items?q=" + tag.toLowerCase()).path("totalElements").asInt()).isEqualTo(3);
		assertThat(get(Role.OWNER, "/api/masters/items?q=" + tag + "&type=FINISHED").path("totalElements").asInt()).isEqualTo(2);
		assertThat(get(Role.OWNER, "/api/masters/items?q=" + tag + "&type=FINISHED&active=true").path("totalElements").asInt()).isEqualTo(1);
		JsonNode page = get(Role.OWNER, "/api/masters/items?q=" + tag + "&size=2&page=1&sort=name,asc");
		assertThat(page.path("content").size()).isEqualTo(1);
		assertThat(page.path("totalPages").asInt()).isEqualTo(2);
		assertCode(call(400, Role.OWNER, HttpMethod.GET, "/api/masters/items?type=WIDGET", null), "INVALID_FILTER");
	}

	// ---- parties ----

	@Test
	void aGstinMustHaveAValidCheckDigit() {
		Map<String, Object> body = partyBody("CUSTOMER", "27");
		String valid = (String) body.get("gstin");
		char last = valid.charAt(14);
		body.put("gstin", valid.substring(0, 14) + (last == 'A' ? 'B' : 'A'));
		JsonNode error = post(400, Role.OWNER, "/api/masters/parties", body);
		assertThat(error.path("fieldErrors").path("gstin").asString()).contains("GSTIN");
		body.put("gstin", "NOT-A-GSTIN");
		assertThat(post(400, Role.OWNER, "/api/masters/parties", body).path("fieldErrors").has("gstin")).isTrue();
	}

	@Test
	void aGstinMustMatchThePartyStateAndPan() {
		Map<String, Object> wrongState = partyBody("CUSTOMER", "27");
		wrongState.put("stateCode", "24");
		assertCode(post(422, Role.OWNER, "/api/masters/parties", wrongState), "GSTIN_STATE_MISMATCH");

		Map<String, Object> wrongPan = partyBody("CUSTOMER", "27");
		wrongPan.put("pan", "ZZZZZ9999Z");
		assertCode(post(422, Role.OWNER, "/api/masters/parties", wrongPan), "GSTIN_PAN_MISMATCH");
	}

	@Test
	void theSameGstinCannotBeUsedByTwoParties() {
		Map<String, Object> first = partyBody("CUSTOMER", "27");
		post(201, Role.OWNER, "/api/masters/parties", first);
		Map<String, Object> second = partyBody("VENDOR", "27");
		second.put("gstin", first.get("gstin"));
		assertCode(post(409, Role.OWNER, "/api/masters/parties", second), "DUPLICATE_GSTIN");
	}

	@Test
	void aPartyWithoutAGstinIsAllowed() {
		Map<String, Object> body = partyBody("CUSTOMER", "27");
		body.put("gstin", "");
		assertThat(post(201, Role.OWNER, "/api/masters/parties", body).path("gstin").isNull()).isTrue();
		Map<String, Object> another = partyBody("CUSTOMER", "27");
		another.put("gstin", null);
		post(201, Role.OWNER, "/api/masters/parties", another);
	}

	@Test
	void msmeRegistrationNeedsAVendorAndAnUdyamNumber() {
		assertCode(post(422, Role.OWNER, "/api/masters/parties", merge(partyBody("VENDOR", "27"), "msmeRegistered", true)), "UDYAM_REQUIRED");
		assertCode(post(422, Role.OWNER, "/api/masters/parties",
				merge(partyBody("CUSTOMER", "27"), "msmeRegistered", true, "udyamNumber", "UDYAM-MH-12-0012345")), "MSME_VENDOR_ONLY");
		assertThat(post(400, Role.OWNER, "/api/masters/parties",
				merge(partyBody("VENDOR", "27"), "msmeRegistered", true, "udyamNumber", "UDYAM-123")).path("fieldErrors").has("udyamNumber")).isTrue();
		post(201, Role.OWNER, "/api/masters/parties",
				merge(partyBody("VENDOR", "27"), "msmeRegistered", true, "udyamNumber", "UDYAM-MH-12-0012345"));
	}

	@Test
	void rejectsBadContactAndCreditDetails() {
		JsonNode error = post(400, Role.OWNER, "/api/masters/parties", merge(partyBody("CUSTOMER", "27"),
				"email", "nope", "phone", "12", "pincode", "4110", "creditDays", 900, "creditLimit", -1, "stateCode", "MH"));
		assertThat(error.path("fieldErrors").propertyNames()).contains("email", "phone", "pincode", "creditDays", "creditLimit", "stateCode");
	}

	// ---- other masters and the audit trail ----

	@Test
	void aTaxRateIsUniquePerHsnAndDate() {
		Map<String, Object> rate = obj("hsnCode", "7" + unique("").substring(1, 4), "ratePct", 18, "effectiveFrom", "2025-09-22");
		post(201, Role.OWNER, "/api/masters/tax-rates", rate);
		assertCode(post(409, Role.OWNER, "/api/masters/tax-rates", rate), "DUPLICATE");
		rate.put("ratePct", 41);
		assertThat(post(400, Role.OWNER, "/api/masters/tax-rates", rate).path("fieldErrors").has("ratePct")).isTrue();
	}

	@Test
	void aNewListCanBeAddedWithoutCode() {
		String type = "MACHINE_GROUP";
		post(201, Role.OWNER, "/api/masters/lookups", obj("lookupType", type, "code", unique("G"), "name", "Presses", "sortOrder", 1));
		assertThat(get(Role.SALES, "/api/masters/lookups?lookupType=" + type).path("totalElements").asInt()).isGreaterThanOrEqualTo(1);
		assertThat(post(400, Role.OWNER, "/api/masters/lookups", obj("lookupType", "bad type", "code", "A", "name", "B"))
				.path("fieldErrors").has("lookupType")).isTrue();
	}

	@Test
	void everyMasterChangeIsWrittenToTheAuditTrail() {
		Map<String, Object> body = itemBody("RAW_MATERIAL");
		JsonNode created = post(201, Role.OWNER, "/api/masters/items", body);
		body.put("name", "Audited name");
		put(200, Role.OWNER, "/api/masters/items/" + created.path("id").asLong(), body);

		JsonNode latest = get(Role.OWNER, "/api/admin/audit?entityType=Item&size=1").path("content").get(0);
		assertThat(latest.path("action").asString()).isEqualTo("UPDATE");
		assertThat(latest.path("actor").asString()).isEqualTo(email(Role.OWNER));
		assertThat(latest.path("changes").asString()).contains("Audited name").contains("Test item");
	}

	@Test
	void theCheckDigitHelperAgreesWithTheValidator() {
		String gstin = GstinValidator.withCheckDigit("27AAPFU0939F1Z");
		assertThat(gstin).isEqualTo("27AAPFU0939F1ZV");
		assertThat(GstinValidator.isValid(gstin)).isTrue();
		assertThat(GstinValidator.isValid("27AAPFU0939F1ZA")).isFalse();
		assertThat(GstinValidator.isValid("27aapfu0939f1zv")).isFalse();
		assertThat(GstinValidator.isValid(null)).isFalse();
	}

	private static Map<String, Object> merge(Map<String, Object> base, Object... overrides) {
		base.putAll(obj(overrides));
		return base;
	}
}
