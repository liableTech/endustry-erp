package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** Order to cash (BRD 9.1): quotation, sales order, invoice, payment, and everything that should stop them. */
class SalesFlowTest extends ApiTest {

	private static final String QUOTES = "/api/sales/docs/quotation";
	private static final String ORDERS = "/api/sales/docs/sales-order";
	private static final String INVOICES = "/api/sales/docs/sales-invoice";

	@Test
	void quotationToCashEndToEnd() {
		long warehouse = warehouse();
		long shaft = item("FINISHED");
		long customer = party("CUSTOMER", "27");
		stockIn(shaft, warehouse, 100);

		// Quotation: 10 x 1000 less 10% = 9000 taxable, 18% GST split equally within the state.
		Map<String, Object> body = docBody(customer, warehouse, obj("itemId", shaft, "qty", 10, "rate", 1000, "discountPct", 10));
		JsonNode quote = post(201, Role.SALES, QUOTES, body);
		assertThat(quote.path("docNo").asString()).startsWith("QT/").hasSizeLessThanOrEqualTo(16);
		assertThat(quote.path("status").asString()).isEqualTo("DRAFT");
		assertAmount(quote.path("subTotal"), "10000");
		assertAmount(quote.path("discountTotal"), "1000");
		assertAmount(quote.path("taxableTotal"), "9000");
		assertAmount(quote.path("cgst"), "810");
		assertAmount(quote.path("sgst"), "810");
		assertAmount(quote.path("igst"), "0");
		assertAmount(quote.path("grandTotal"), "10620");
		long quoteId = quote.path("id").asLong();

		JsonNode sent = act(200, Role.SALES, QUOTES, quoteId, "send");
		assertThat(sent.path("dueDate").asString()).isEqualTo(TODAY.plusDays(30).toString());
		assertThat(act(200, Role.SALES, QUOTES, quoteId, "accept").path("derivations").toString()).contains("sales-order");

		// Sales order raised from the quotation carries its lines and price.
		JsonNode order = post(201, Role.SALES, ORDERS + "/from/" + quoteId, null);
		assertThat(order.path("sourceDoc").path("docNo").asString()).isEqualTo(quote.path("docNo").asString());
		assertAmount(order.path("grandTotal"), "10620");
		long orderId = order.path("id").asLong();
		assertThat(act(200, Role.SALES, ORDERS, orderId, "confirm").path("status").asString()).isEqualTo("CONFIRMED");
		assertThat(get(Role.SALES, QUOTES + "/" + quoteId).path("status").asString()).isEqualTo("FULFILLED");

		// Part invoice for 4, then the remaining 6.
		JsonNode draft = post(201, Role.SALES, INVOICES + "/from/" + orderId, null);
		Map<String, Object> partial = bodyOf(draft);
		((List<Map<String, Object>>) partial.get("lines")).get(0).put("qty", 4);
		put(200, Role.SALES, INVOICES + "/" + draft.path("id").asLong(), partial);
		JsonNode invoice = act(200, Role.SALES, INVOICES, draft.path("id").asLong(), "post");
		assertThat(invoice.path("status").asString()).isEqualTo("POSTED");
		assertThat(invoice.path("dueDate").asString()).isEqualTo(TODAY.plusDays(30).toString());
		assertAmount(invoice.path("grandTotal"), "4248");
		assertThat(available(shaft, warehouse)).isEqualByComparingTo("96");
		assertThat(get(Role.SALES, ORDERS + "/" + orderId).path("status").asString()).isEqualTo("PARTLY_FULFILLED");

		JsonNode rest = post(201, Role.SALES, INVOICES + "/from/" + orderId, null);
		assertAmount(rest.path("lines").get(0).path("qty"), "6");
		act(200, Role.SALES, INVOICES, rest.path("id").asLong(), "post");
		assertThat(available(shaft, warehouse)).isEqualByComparingTo("90");
		assertThat(get(Role.SALES, ORDERS + "/" + orderId).path("status").asString()).isEqualTo("FULFILLED");
		assertCode(post(409, Role.SALES, INVOICES + "/from/" + orderId, null), "INVALID_TRANSITION");

		// Receipt in two parts.
		String payments = INVOICES + "/" + invoice.path("id").asLong() + "/payments";
		JsonNode partPaid = post(200, Role.ACCOUNTS, payments, obj("amount", 1000, "paymentDate", TODAY.toString(), "mode", "NEFT"));
		assertThat(partPaid.path("status").asString()).isEqualTo("PARTLY_PAID");
		assertAmount(partPaid.path("balance"), "3248");
		JsonNode paid = post(200, Role.ACCOUNTS, payments, obj("amount", 3248, "paymentDate", TODAY.toString()));
		assertThat(paid.path("status").asString()).isEqualTo("PAID");
		assertThat(paid.path("allowedActions").size()).isZero();
		assertThat(get(Role.ACCOUNTS, payments).size()).isEqualTo(2);
	}

	@Test
	void aSaleToAnotherStateIsChargedIgst() {
		long item = item("FINISHED");
		JsonNode quote = post(201, Role.SALES, QUOTES, docBody(party("CUSTOMER", "33"), null, line(item, 3, 333.33)));
		assertAmount(quote.path("taxableTotal"), "999.99");
		assertAmount(quote.path("igst"), "180.00");
		assertAmount(quote.path("cgst"), "0");
		assertAmount(quote.path("grandTotal"), "1179.99");
		assertThat(quote.path("placeOfSupply").asString()).isEqualTo("33");
	}

	@Test
	void cgstAndSgstAlwaysAddBackToTheTax() {
		// 1 x 0.25 at 18% is 0.045 -> 0.05 of tax, which cannot be halved evenly.
		long item = item("FINISHED");
		JsonNode quote = post(201, Role.SALES, QUOTES, docBody(party("CUSTOMER", "27"), null, line(item, 1, 0.25)));
		assertThat(quote.path("cgst").decimalValue().add(quote.path("sgst").decimalValue()))
				.isEqualByComparingTo(quote.path("lines").get(0).path("taxAmount").decimalValue());
	}

	@Test
	void theGstRateInForceOnTheDocumentDateIsUsed() {
		String hsn = "48" + unique("").substring(2, 4);
		long item = item("FINISHED", "hsnCode", hsn, "gstRate", 28);
		post(201, Role.OWNER, "/api/masters/tax-rates", obj("hsnCode", hsn, "ratePct", 12, "effectiveFrom", TODAY.minusDays(60).toString()));
		post(201, Role.OWNER, "/api/masters/tax-rates", obj("hsnCode", hsn, "ratePct", 5, "effectiveFrom", TODAY.minusDays(5).toString()));
		long customer = party("CUSTOMER", "27");

		Map<String, Object> before = docBody(customer, null, line(item, 1, 1000));
		before.put("docDate", TODAY.minusDays(10).toString());
		assertAmount(post(201, Role.SALES, QUOTES, before).path("lines").get(0).path("taxRatePct"), "12");
		assertAmount(post(201, Role.SALES, QUOTES, docBody(customer, null, line(item, 1, 1000))).path("lines").get(0).path("taxRatePct"), "5");
		// Before any dated rate applies, the rate on the item is the fallback.
		Map<String, Object> early = docBody(customer, null, line(item, 1, 1000));
		early.put("docDate", TODAY.minusDays(90).toString());
		assertAmount(post(201, Role.SALES, QUOTES, early).path("lines").get(0).path("taxRatePct"), "28");
	}

	@Test
	void rejectsDocumentsWithBadInput() {
		long item = item("FINISHED");
		long customer = party("CUSTOMER", "27");

		JsonNode noLines = post(400, Role.SALES, ORDERS, obj("partyId", customer, "docDate", TODAY.toString(), "lines", List.of()));
		assertThat(noLines.path("fieldErrors").path("lines").asString()).contains("at least one line");

		Map<String, Object> future = docBody(customer, null, line(item, 1, 10));
		future.put("docDate", TODAY.plusDays(1).toString());
		assertThat(post(400, Role.SALES, ORDERS, future).path("fieldErrors").path("docDate").asString()).contains("future");

		JsonNode badLine = post(400, Role.SALES, ORDERS, docBody(customer, null, obj("itemId", item, "qty", 0, "rate", -5, "discountPct", 120)));
		assertThat(badLine.path("fieldErrors").propertyNames()).contains("lines[0].qty", "lines[0].rate", "lines[0].discountPct");

		assertThat(post(400, Role.SALES, ORDERS, obj("docDate", TODAY.toString(), "lines", List.of(obj("qty", 1, "rate", 1))))
				.path("fieldErrors").propertyNames()).contains("partyId", "lines[0].itemId");

		Map<String, Object> dueEarly = docBody(customer, null, line(item, 1, 10));
		dueEarly.put("dueDate", TODAY.minusDays(1).toString());
		assertCode(post(422, Role.SALES, ORDERS, dueEarly), "DUE_BEFORE_DATE");
	}

	@Test
	void rejectsTheWrongInactiveOrUnknownPartyAndItem() {
		long item = item("FINISHED");
		assertCode(post(422, Role.SALES, ORDERS, docBody(party("VENDOR", "27"), null, line(item, 1, 10))), "WRONG_PARTY_TYPE");
		assertCode(post(422, Role.SALES, ORDERS, docBody(party("CUSTOMER", "27", "active", false), null, line(item, 1, 10))), "PARTY_INACTIVE");
		assertCode(post(422, Role.SALES, ORDERS, docBody(99999999L, null, line(item, 1, 10))), "UNKNOWN_PARTY");
		long customer = party("CUSTOMER", "27");
		assertCode(post(422, Role.SALES, ORDERS, docBody(customer, null, line(99999999L, 1, 10))), "UNKNOWN_ITEM");
		assertCode(post(422, Role.SALES, ORDERS, docBody(customer, null, line(item("FINISHED", "active", false), 1, 10))), "ITEM_INACTIVE");
		assertCode(post(422, Role.SALES, ORDERS, docBody(customer, 99999999L, line(item, 1, 10))), "UNKNOWN_WAREHOUSE");
		// A party that is both customer and vendor works on either side.
		post(201, Role.SALES, ORDERS, docBody(party("BOTH", "27"), null, line(item, 1, 10)));
	}

	@Test
	void anOrderOverTheCreditLimitIsBlockedUntilTheOwnerOverrides() {
		long item = item("FINISHED");
		long customer = party("CUSTOMER", "27", "creditLimit", 50000);
		long order = post(201, Role.SALES, ORDERS, docBody(customer, null, line(item, 100, 1000))).path("id").asLong();

		JsonNode blocked = act(422, Role.SALES, ORDERS, order, "confirm");
		assertCode(blocked, "CREDIT_LIMIT_EXCEEDED");
		assertAmount(blocked.path("details").path("creditLimit"), "50000");
		assertAmount(blocked.path("details").path("thisDocument"), "118000");
		// Asking for an override is not enough; only the Owner's request counts.
		assertCode(post(422, Role.SALES, ORDERS + "/" + order + "/actions/confirm", Map.of("override", true)), "CREDIT_LIMIT_EXCEEDED");
		assertThat(get(Role.SALES, ORDERS + "/" + order).path("status").asString()).isEqualTo("DRAFT");

		JsonNode confirmed = post(200, Role.OWNER, ORDERS + "/" + order + "/actions/confirm", Map.of("override", true));
		assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
		assertThat(confirmed.path("alertNote").asString()).contains("overridden by " + email(Role.OWNER));
	}

	@Test
	void unpaidInvoicesCountTowardsTheCreditLimit() {
		long warehouse = warehouse();
		long item = item("FINISHED");
		stockIn(item, warehouse, 100);
		long customer = party("CUSTOMER", "27", "creditLimit", 20000);

		// A direct invoice of 11,800 is within the limit; a second one would take exposure to 23,600.
		long first = post(201, Role.SALES, INVOICES, docBody(customer, warehouse, line(item, 10, 1000))).path("id").asLong();
		act(200, Role.SALES, INVOICES, first, "post");
		long second = post(201, Role.SALES, INVOICES, docBody(customer, warehouse, line(item, 10, 1000))).path("id").asLong();
		assertAmount(act(422, Role.SALES, INVOICES, second, "post").path("details").path("outstanding"), "11800");

		// Once the first is paid the second goes through.
		post(200, Role.ACCOUNTS, INVOICES + "/" + first + "/payments", obj("amount", 11800, "paymentDate", TODAY.toString()));
		act(200, Role.SALES, INVOICES, second, "post");
	}

	@Test
	void anInvoiceCannotSellStockThatIsNotThere() {
		long warehouse = warehouse();
		long item = item("FINISHED");
		stockIn(item, warehouse, 5);
		long invoice = post(201, Role.SALES, INVOICES, docBody(party("CUSTOMER", "27"), warehouse, line(item, 8, 100))).path("id").asLong();

		JsonNode error = act(422, Role.SALES, INVOICES, invoice, "post");
		assertCode(error, "INSUFFICIENT_STOCK");
		assertAmount(error.path("details").path("available"), "5");
		// The failed posting left nothing behind.
		assertThat(get(Role.SALES, INVOICES + "/" + invoice).path("status").asString()).isEqualTo("DRAFT");
		assertThat(available(item, warehouse)).isEqualByComparingTo("5");
	}

	@Test
	void anInvoiceOfGoodsNeedsAWarehouseButAServiceInvoiceDoesNot() {
		long customer = party("CUSTOMER", "27");
		long goods = post(201, Role.SALES, INVOICES, docBody(customer, null, line(item("FINISHED"), 1, 100))).path("id").asLong();
		assertCode(act(422, Role.SALES, INVOICES, goods, "post"), "WAREHOUSE_REQUIRED");

		long service = post(201, Role.SALES, INVOICES, docBody(customer, null, line(item("SERVICE"), 1, 90000))).path("id").asLong();
		JsonNode posted = act(200, Role.SALES, INVOICES, service, "post");
		assertThat(posted.path("status").asString()).isEqualTo("POSTED");
		assertThat(posted.path("ewayBillRequired").asBoolean()).isFalse();
	}

	@Test
	void goodsAboveTheThresholdAreFlaggedForAnEwayBill() {
		long warehouse = warehouse();
		long item = item("FINISHED");
		stockIn(item, warehouse, 100);
		long customer = party("CUSTOMER", "27");
		long small = post(201, Role.SALES, INVOICES, docBody(customer, warehouse, line(item, 1, 40000))).path("id").asLong();
		assertThat(act(200, Role.SALES, INVOICES, small, "post").path("ewayBillRequired").asBoolean()).isFalse();
		long large = post(201, Role.SALES, INVOICES, docBody(customer, warehouse, line(item, 1, 45000))).path("id").asLong();
		assertThat(act(200, Role.SALES, INVOICES, large, "post").path("ewayBillRequired").asBoolean()).isTrue();
	}

	@Test
	void twoInvoicesCannotTogetherExceedTheOrder() {
		long warehouse = warehouse();
		long item = item("FINISHED");
		stockIn(item, warehouse, 100);
		long order = post(201, Role.SALES, ORDERS, docBody(party("CUSTOMER", "27"), warehouse, line(item, 10, 100))).path("id").asLong();
		act(200, Role.SALES, ORDERS, order, "confirm");

		long first = post(201, Role.SALES, INVOICES + "/from/" + order, null).path("id").asLong();
		long second = post(201, Role.SALES, INVOICES + "/from/" + order, null).path("id").asLong();
		act(200, Role.SALES, INVOICES, first, "post");
		assertCode(act(422, Role.SALES, INVOICES, second, "post"), "OVER_INVOICING");
		assertThat(available(item, warehouse)).isEqualByComparingTo("90");
	}

	@Test
	void aDerivedInvoiceMustKeepThePartyAndItemsOfItsOrder() {
		long warehouse = warehouse();
		long item = item("FINISHED");
		long order = post(201, Role.SALES, ORDERS, docBody(party("CUSTOMER", "27"), warehouse, line(item, 10, 100))).path("id").asLong();
		act(200, Role.SALES, ORDERS, order, "confirm");
		JsonNode draft = post(201, Role.SALES, INVOICES + "/from/" + order, null);
		String path = INVOICES + "/" + draft.path("id").asLong();

		Map<String, Object> otherParty = bodyOf(draft);
		otherParty.put("partyId", party("CUSTOMER", "27"));
		assertCode(put(422, Role.SALES, path, otherParty), "PARTY_MISMATCH");

		Map<String, Object> otherItem = bodyOf(draft);
		((List<Map<String, Object>>) otherItem.get("lines")).get(0).put("itemId", item("FINISHED"));
		assertCode(put(422, Role.SALES, path, otherItem), "LINE_NOT_ON_SOURCE");
	}

	@Test
	void documentsFollowTheirLifecycleAndNothingElse() {
		long item = item("FINISHED");
		long customer = party("CUSTOMER", "27");
		JsonNode order = post(201, Role.SALES, ORDERS, docBody(customer, null, line(item, 1, 100)));
		long id = order.path("id").asLong();
		assertThat(order.path("allowedActions").toString()).contains("confirm").contains("cancel");

		assertCode(act(409, Role.SALES, ORDERS, id, "post"), "INVALID_TRANSITION");
		assertCode(post(409, Role.SALES, INVOICES + "/from/" + id, null), "INVALID_TRANSITION");
		assertCode(post(409, Role.ACCOUNTS, ORDERS + "/" + id + "/payments", obj("amount", 1, "paymentDate", TODAY.toString())), "NOT_PAYABLE");

		act(200, Role.SALES, ORDERS, id, "confirm");
		assertCode(put(409, Role.SALES, ORDERS + "/" + id, docBody(customer, null, line(item, 2, 100))), "NOT_EDITABLE");
		assertCode(act(409, Role.SALES, ORDERS, id, "confirm"), "INVALID_TRANSITION");
		assertThat(act(200, Role.SALES, ORDERS, id, "cancel").path("status").asString()).isEqualTo("CANCELLED");
		assertCode(act(409, Role.SALES, ORDERS, id, "cancel"), "INVALID_TRANSITION");

		// A document is reachable only under its own kind.
		assertCode(call(404, Role.SALES, org.springframework.http.HttpMethod.GET, QUOTES + "/" + id, null), "NOT_FOUND");
		assertCode(call(404, Role.SALES, org.springframework.http.HttpMethod.GET, "/api/sales/docs/purchase-order", null), "NOT_FOUND");
		assertCode(post(422, Role.SALES, QUOTES + "/from/" + id, null), "NOT_DERIVABLE");
	}

	@Test
	void anExpiredQuotationCannotBeAccepted() {
		Map<String, Object> body = docBody(party("CUSTOMER", "27"), null, line(item("FINISHED"), 1, 100));
		body.put("docDate", TODAY.minusDays(40).toString());
		body.put("dueDate", TODAY.minusDays(10).toString());
		long quote = post(201, Role.SALES, QUOTES, body).path("id").asLong();
		act(200, Role.SALES, QUOTES, quote, "send");
		assertCode(act(422, Role.SALES, QUOTES, quote, "accept"), "QUOTATION_EXPIRED");
		assertThat(act(200, Role.SALES, QUOTES, quote, "reject").path("status").asString()).isEqualTo("REJECTED");
	}

	@Test
	void paymentsCannotExceedTheBalanceOrPredateTheInvoice() {
		long invoice = post(201, Role.SALES, INVOICES, docBody(party("CUSTOMER", "27"), null, line(item("SERVICE"), 1, 1000))).path("id").asLong();
		String payments = INVOICES + "/" + invoice + "/payments";
		assertCode(post(409, Role.ACCOUNTS, payments, obj("amount", 100, "paymentDate", TODAY.toString())), "NOT_PAYABLE");
		act(200, Role.SALES, INVOICES, invoice, "post");

		assertCode(post(422, Role.ACCOUNTS, payments, obj("amount", 1180.01, "paymentDate", TODAY.toString())), "OVERPAYMENT");
		assertCode(post(422, Role.ACCOUNTS, payments, obj("amount", 100, "paymentDate", TODAY.minusDays(1).toString())), "PAYMENT_BEFORE_INVOICE");
		JsonNode invalid = post(400, Role.ACCOUNTS, payments, obj("amount", 0, "paymentDate", TODAY.plusDays(2).toString()));
		assertThat(invalid.path("fieldErrors").propertyNames()).contains("amount", "paymentDate");
		assertCode(post(403, Role.STORE, payments, obj("amount", 100, "paymentDate", TODAY.toString())), "FORBIDDEN");
	}

	@Test
	void listsFilterByStatusPartyAndText() {
		long item = item("FINISHED");
		long customer = party("CUSTOMER", "27");
		String poNumber = unique("CUSTPO");
		Map<String, Object> body = docBody(customer, null, line(item, 1, 100));
		body.put("referenceNo", poNumber);
		long first = post(201, Role.SALES, ORDERS, body).path("id").asLong();
		post(201, Role.SALES, ORDERS, docBody(customer, null, line(item, 2, 100)));
		act(200, Role.SALES, ORDERS, first, "confirm");

		assertThat(get(Role.SALES, ORDERS + "?partyId=" + customer).path("totalElements").asInt()).isEqualTo(2);
		assertThat(get(Role.SALES, ORDERS + "?partyId=" + customer + "&status=CONFIRMED").path("totalElements").asInt()).isEqualTo(1);
		assertThat(get(Role.SALES, ORDERS + "?partyId=" + customer + "&status=draft,confirmed").path("totalElements").asInt()).isEqualTo(2);
		assertThat(get(Role.SALES, ORDERS + "?q=" + poNumber.toLowerCase()).path("totalElements").asInt()).isEqualTo(1);
		assertCode(call(400, Role.SALES, org.springframework.http.HttpMethod.GET, ORDERS + "?status=SHIPPED", null), "INVALID_FILTER");
	}

	@Test
	void orderRaisedFromQuotationWithoutWarehouseGetsOne() {
		warehouse();
		long shaft = item("FINISHED");
		long customer = party("CUSTOMER", "27");
		JsonNode quote = post(201, Role.SALES, QUOTES, docBody(customer, null, obj("itemId", shaft, "qty", 1, "rate", 100, "discountPct", 0)));
		long quoteId = quote.path("id").asLong();
		act(200, Role.SALES, QUOTES, quoteId, "send");
		JsonNode order = post(201, Role.SALES, ORDERS + "/from/" + quoteId, null);
		assertThat(order.path("warehouse").path("id").asLong()).isPositive();
	}
}
