package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** The filters every table sends: any of several values, text containing, and ranges of numbers or dates. */
class TableFiltersTest extends ApiTest {

	private static final String ITEMS = "/api/masters/items";
	private static final String INVOICES = "/api/sales/docs/sales-invoice";

	@Test
	void aMasterListTakesSeveralFiltersTogether() {
		String name = unique("Filterable");
		Map<String, Object> cheap = itemBody("RAW_MATERIAL");
		cheap.putAll(Map.of("name", name + " washer", "salePrice", 40, "gstRate", 5));
		Map<String, Object> dear = itemBody("FINISHED");
		dear.putAll(Map.of("name", name + " gearbox", "salePrice", 9000, "gstRate", 28));
		post(201, Role.OWNER, ITEMS, cheap);
		post(201, Role.OWNER, ITEMS, dear);

		String mine = ITEMS + "?name.like=" + name.toLowerCase();
		assertThat(count(mine)).isEqualTo(2);
		assertThat(count(mine + "&salePrice.from=100")).isEqualTo(1);
		assertThat(count(mine + "&salePrice.to=100")).isEqualTo(1);
		assertThat(count(mine + "&salePrice.from=10&salePrice.to=10000&gstRate.from=20")).isEqualTo(1);
		assertThat(count(mine + "&type=RAW_MATERIAL,FINISHED&active=true")).isEqualTo(2);
		assertThat(get(Role.OWNER, mine + "&type=FINISHED&gstRate.to=30").path("content").get(0).path("name").asString()).endsWith("gearbox");
		assertThat(count(mine + "&salePrice.from=100&type=RAW_MATERIAL")).isZero();

		assertCode(call(400, Role.OWNER, HttpMethod.GET, mine + "&salePrice.from=cheap", null), "INVALID_FILTER");
		// A filter on something the record does not have is ignored, not an error.
		assertThat(count(mine + "&colour.like=red")).isEqualTo(2);
	}

	@Test
	void aDocumentListFiltersByPartyDateAndTotal() {
		long all = count(INVOICES);
		assertThat(count(INVOICES + "?docDate.from=2999-01-01")).isZero();
		assertThat(count(INVOICES + "?docDate.to=2999-01-01&grandTotal.from=0")).isEqualTo(all);
		assertThat(count(INVOICES + "?grandTotal.from=999999999999")).isZero();
		assertThat(count(INVOICES + "?partyId=999999,999998")).isZero();
		assertThat(count(INVOICES + "?dueDate.from=1990-01-01&dueDate.to=1990-01-02")).isZero();
		assertCode(call(400, Role.OWNER, HttpMethod.GET, INVOICES + "?docDate.from=yesterday", null), "INVALID_FILTER");
	}

	private long count(String path) {
		JsonNode page = get(Role.OWNER, path);
		return page.path("totalElements").asLong();
	}
}
