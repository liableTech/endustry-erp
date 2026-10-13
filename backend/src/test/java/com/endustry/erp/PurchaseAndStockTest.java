package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** Procure to pay (BRD 9.2), incoming quality, and the stock ledger. */
class PurchaseAndStockTest extends ApiTest {

	private static final String ORDERS = "/api/purchase/docs/purchase-order";
	private static final String RECEIPTS = "/api/inventory/docs/grn";
	private static final String BILLS = "/api/purchase/docs/purchase-invoice";
	private static final String INSPECTIONS = "/api/quality/inspections";

	// ---- procure to pay ----

	@Test
	void purchaseOrderToPaymentWithIncomingInspection() {
		long warehouse = warehouse();
		long steel = item("RAW_MATERIAL", "batchTracked", true, "qcRequired", true);
		long vendor = party("VENDOR", "27");

		long order = post(201, Role.PURCHASE, ORDERS, docBody(vendor, warehouse, line(steel, 100, 50))).path("id").asLong();
		assertThat(act(200, Role.PURCHASE, ORDERS, order, "approve").path("status").asString()).isEqualTo("APPROVED");

		// Store receives 100; it sits in QC, not in usable stock.
		JsonNode receipt = post(201, Role.STORE, RECEIPTS + "/from/" + order, null);
		JsonNode posted = act(200, Role.STORE, RECEIPTS, receipt.path("id").asLong(), "post");
		String batch = posted.path("lines").get(0).path("batchNo").asString();
		assertThat(batch).startsWith(posted.path("docNo").asString());
		assertThat(available(steel, warehouse)).isEqualByComparingTo("0");
		assertThat(stockOf(steel, warehouse, "QC_PENDING")).isEqualByComparingTo("100");
		assertThat(get(Role.PURCHASE, ORDERS + "/" + order).path("status").asString()).isEqualTo("FULFILLED");

		// Quality accepts 92 and rejects 8.
		JsonNode inspection = pendingInspection(steel);
		assertThat(inspection.path("grnNo").asString()).isEqualTo(posted.path("docNo").asString());
		JsonNode done = post(200, Role.QUALITY, INSPECTIONS + "/" + inspection.path("id").asLong() + "/complete",
				obj("acceptedQty", 92, "rejectedQty", 8, "reasonCode", "SURFACE", "remarks", "Rust on 8 bars"));
		assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
		assertThat(done.path("inspectedBy").asString()).isEqualTo(email(Role.QUALITY));
		assertThat(available(steel, warehouse)).isEqualByComparingTo("92");
		assertThat(stockOf(steel, warehouse, "REJECTED")).isEqualByComparingTo("8");
		assertThat(stockOf(steel, warehouse, "QC_PENDING")).isEqualByComparingTo("0");

		// Vendor invoice booked against the receipt and paid.
		JsonNode bill = post(201, Role.PURCHASE, BILLS + "/from/" + receipt.path("id").asLong(), null);
		Map<String, Object> body = bodyOf(bill);
		body.put("referenceNo", unique("VINV"));
		put(200, Role.PURCHASE, BILLS + "/" + bill.path("id").asLong(), body);
		JsonNode booked = act(200, Role.PURCHASE, BILLS, bill.path("id").asLong(), "post");
		assertAmount(booked.path("grandTotal"), "5900");
		assertThat(booked.path("dueDate").asString()).isEqualTo(TODAY.plusDays(30).toString());
		assertThat(booked.path("alertNote").isNull()).isTrue();
		JsonNode paid = post(200, Role.ACCOUNTS, BILLS + "/" + bill.path("id").asLong() + "/payments",
				obj("amount", 5900, "paymentDate", TODAY.toString(), "mode", "NEFT"));
		assertThat(paid.path("status").asString()).isEqualTo("PAID");
	}

	@Test
	void aLargeOrderNeedsTheOwnersApproval() {
		long order = post(201, Role.PURCHASE, ORDERS, docBody(party("VENDOR", "27"), null, line(item("RAW_MATERIAL"), 1000, 500))).path("id").asLong();
		assertCode(act(403, Role.PURCHASE, ORDERS, order, "approve"), "APPROVAL_LIMIT_EXCEEDED");
		assertThat(get(Role.PURCHASE, ORDERS + "/" + order).path("status").asString()).isEqualTo("DRAFT");
		assertThat(act(200, Role.OWNER, ORDERS, order, "approve").path("status").asString()).isEqualTo("APPROVED");
	}

	@Test
	void theStoreCanReceiveGoodsButNotRaiseOrApproveOrders() {
		long vendor = party("VENDOR", "27");
		long item = item("RAW_MATERIAL");
		assertCode(post(403, Role.STORE, ORDERS, docBody(vendor, null, line(item, 1, 10))), "FORBIDDEN");
		long order = post(201, Role.PURCHASE, ORDERS, docBody(vendor, warehouse(), line(item, 10, 10))).path("id").asLong();
		assertCode(act(403, Role.STORE, ORDERS, order, "approve"), "FORBIDDEN");
		// Nothing can be received until the order is approved.
		assertCode(post(409, Role.STORE, RECEIPTS + "/from/" + order, null), "INVALID_TRANSITION");
		act(200, Role.PURCHASE, ORDERS, order, "approve");
		post(201, Role.STORE, RECEIPTS + "/from/" + order, null);
		// Purchase can see receipts but not post them.
		get(Role.PURCHASE, RECEIPTS);
		assertCode(post(403, Role.PURCHASE, RECEIPTS + "/from/" + order, null), "FORBIDDEN");
	}

	@Test
	void receiptsAreLimitedToTheOrderPlusTolerance() {
		long warehouse = warehouse();
		long item = item("RAW_MATERIAL");
		long order = post(201, Role.PURCHASE, ORDERS, docBody(party("VENDOR", "27"), warehouse, line(item, 100, 10))).path("id").asLong();
		act(200, Role.PURCHASE, ORDERS, order, "approve");
		JsonNode receipt = post(201, Role.STORE, RECEIPTS + "/from/" + order, null);
		String path = RECEIPTS + "/" + receipt.path("id").asLong();

		// Default tolerance is 5%: 106 is refused, 105 is accepted.
		put(200, Role.STORE, path, withQty(receipt, 106));
		JsonNode over = act(422, Role.STORE, RECEIPTS, receipt.path("id").asLong(), "post");
		assertCode(over, "OVER_RECEIPT");
		assertAmount(over.path("details").path("pending"), "100");
		assertThat(available(item, warehouse)).isEqualByComparingTo("0");

		put(200, Role.STORE, path, withQty(receipt, 105));
		act(200, Role.STORE, RECEIPTS, receipt.path("id").asLong(), "post");
		assertThat(available(item, warehouse)).isEqualByComparingTo("105");
		assertThat(get(Role.PURCHASE, ORDERS + "/" + order).path("status").asString()).isEqualTo("FULFILLED");
	}

	@Test
	void aPartReceiptLeavesTheOrderOpenForTheRest() {
		long warehouse = warehouse();
		long item = item("RAW_MATERIAL");
		long order = post(201, Role.PURCHASE, ORDERS, docBody(party("VENDOR", "27"), warehouse, line(item, 100, 10))).path("id").asLong();
		act(200, Role.PURCHASE, ORDERS, order, "approve");
		JsonNode first = post(201, Role.STORE, RECEIPTS + "/from/" + order, null);
		put(200, Role.STORE, RECEIPTS + "/" + first.path("id").asLong(), withQty(first, 40));
		act(200, Role.STORE, RECEIPTS, first.path("id").asLong(), "post");

		JsonNode po = get(Role.PURCHASE, ORDERS + "/" + order);
		assertThat(po.path("status").asString()).isEqualTo("PARTLY_FULFILLED");
		assertAmount(po.path("lines").get(0).path("pendingQty"), "60");
		assertAmount(post(201, Role.STORE, RECEIPTS + "/from/" + order, null).path("lines").get(0).path("qty"), "60");
	}

	@Test
	void goodsCannotBeReceivedWithoutAnOrderOrAWarehouse() {
		long vendor = party("VENDOR", "27");
		long item = item("RAW_MATERIAL");
		long loose = post(201, Role.STORE, RECEIPTS, docBody(vendor, warehouse(), line(item, 5, 10))).path("id").asLong();
		assertCode(act(422, Role.STORE, RECEIPTS, loose, "post"), "GRN_REQUIRES_PO");

		long order = post(201, Role.PURCHASE, ORDERS, docBody(vendor, null, line(item, 5, 10))).path("id").asLong();
		act(200, Role.PURCHASE, ORDERS, order, "approve");
		long receipt = post(201, Role.STORE, RECEIPTS + "/from/" + order, null).path("id").asLong();
		assertCode(act(422, Role.STORE, RECEIPTS, receipt, "post"), "WAREHOUSE_REQUIRED");
	}

	@Test
	void goodsAreReceivedAtTheOrderedPriceWhateverTheReceiptSays() {
		long warehouse = warehouse();
		long item = item("RAW_MATERIAL");
		long order = post(201, Role.PURCHASE, ORDERS, docBody(party("VENDOR", "27"), warehouse, line(item, 10, 80))).path("id").asLong();
		act(200, Role.PURCHASE, ORDERS, order, "approve");
		JsonNode receipt = post(201, Role.STORE, RECEIPTS + "/from/" + order, null);
		Map<String, Object> tampered = bodyOf(receipt);
		((List<Map<String, Object>>) tampered.get("lines")).get(0).put("rate", 1);
		assertAmount(put(200, Role.STORE, RECEIPTS + "/" + receipt.path("id").asLong(), tampered).path("lines").get(0).path("rate"), "80");
	}

	// ---- incoming inspection ----

	@Test
	void anInspectionMustAccountForEveryUnitAndGiveAReasonForRejects() {
		long steel = item("RAW_MATERIAL", "qcRequired", true);
		receive(steel, warehouse(), party("VENDOR", "27"), 50, 20);
		String complete = INSPECTIONS + "/" + pendingInspection(steel).path("id").asLong() + "/complete";

		assertCode(post(422, Role.QUALITY, complete, obj("acceptedQty", 40, "rejectedQty", 5)), "QTY_MISMATCH");
		assertCode(post(422, Role.QUALITY, complete, obj("acceptedQty", 45, "rejectedQty", 5)), "REASON_REQUIRED");
		assertCode(post(422, Role.QUALITY, complete, obj("acceptedQty", 45, "rejectedQty", 5, "reasonCode", "BAD_VIBES")), "UNKNOWN_REASON");
		assertThat(post(400, Role.QUALITY, complete, obj("acceptedQty", -1)).path("fieldErrors").propertyNames())
				.contains("acceptedQty", "rejectedQty");
		assertCode(post(403, Role.STORE, complete, obj("acceptedQty", 50, "rejectedQty", 0)), "FORBIDDEN");

		post(200, Role.QUALITY, complete, obj("acceptedQty", 50, "rejectedQty", 0));
		assertCode(post(409, Role.QUALITY, complete, obj("acceptedQty", 50, "rejectedQty", 0)), "ALREADY_INSPECTED");
		assertCode(post(404, Role.QUALITY, INSPECTIONS + "/99999999/complete", obj("acceptedQty", 1, "rejectedQty", 0)), "NOT_FOUND");
	}

	// ---- vendor invoices ----

	@Test
	void msmeVendorsGetTheStatutoryDueDate() {
		assertThat(dueDateFor(party("VENDOR", "27", "creditDays", 60))).isEqualTo(TODAY.plusDays(60).toString());
		// Agreed 60 days is capped at 45 for a registered MSME.
		assertThat(dueDateFor(party("VENDOR", "27", "creditDays", 60, "msmeRegistered", true, "msmeAgreedTerms", true,
				"udyamNumber", "UDYAM-MH-12-0012345"))).isEqualTo(TODAY.plusDays(45).toString());
		// Agreed 30 days stays 30.
		assertThat(dueDateFor(party("VENDOR", "27", "creditDays", 30, "msmeRegistered", true, "msmeAgreedTerms", true,
				"udyamNumber", "UDYAM-MH-12-0012346"))).isEqualTo(TODAY.plusDays(30).toString());
		// No written agreement: 15 days, whatever the credit days say.
		assertThat(dueDateFor(party("VENDOR", "27", "creditDays", 60, "msmeRegistered", true, "msmeAgreedTerms", false,
				"udyamNumber", "UDYAM-MH-12-0012347"))).isEqualTo(TODAY.plusDays(15).toString());
	}

	@Test
	void aVendorInvoiceNeedsAUniqueInvoiceNumber() {
		long vendor = party("VENDOR", "27");
		long service = item("SERVICE");
		long first = post(201, Role.PURCHASE, BILLS, docBody(vendor, null, line(service, 1, 100))).path("id").asLong();
		assertCode(act(422, Role.PURCHASE, BILLS, first, "post"), "VENDOR_INVOICE_NO_REQUIRED");

		String number = unique("INV-");
		Map<String, Object> body = docBody(vendor, null, line(service, 1, 100));
		body.put("referenceNo", number);
		put(200, Role.PURCHASE, BILLS + "/" + first, body);
		act(200, Role.PURCHASE, BILLS, first, "post");

		// The same number again from the same vendor, in any letter case, is a duplicate...
		body.put("referenceNo", number.toLowerCase());
		long second = post(201, Role.PURCHASE, BILLS, body).path("id").asLong();
		assertCode(act(409, Role.PURCHASE, BILLS, second, "post"), "DUPLICATE_VENDOR_INVOICE");
		// ...but fine from a different vendor.
		long other = post(201, Role.PURCHASE, BILLS, merge(body, "partyId", party("VENDOR", "27"))).path("id").asLong();
		act(200, Role.PURCHASE, BILLS, other, "post");
	}

	@Test
	void aBilledRateDifferentFromTheOrderIsFlagged() {
		long item = item("RAW_MATERIAL");
		JsonNode receipt = receive(item, warehouse(), party("VENDOR", "27"), 10, 100);
		JsonNode bill = post(201, Role.PURCHASE, BILLS + "/from/" + receipt.path("id").asLong(), null);
		Map<String, Object> body = bodyOf(bill);
		body.put("referenceNo", unique("V"));
		((List<Map<String, Object>>) body.get("lines")).get(0).put("rate", 110);
		put(200, Role.PURCHASE, BILLS + "/" + bill.path("id").asLong(), body);
		JsonNode posted = act(200, Role.PURCHASE, BILLS, bill.path("id").asLong(), "post");
		assertThat(posted.path("alertNote").asString()).contains("Rate variance").contains("ordered 100").contains("billed 110");
	}

	@Test
	void aVendorCannotBeBilledForMoreThanWasReceived() {
		long item = item("RAW_MATERIAL");
		JsonNode receipt = receive(item, warehouse(), party("VENDOR", "27"), 10, 100);
		JsonNode bill = post(201, Role.PURCHASE, BILLS + "/from/" + receipt.path("id").asLong(), null);
		Map<String, Object> body = withQty(bill, 11);
		body.put("referenceNo", unique("V"));
		put(200, Role.PURCHASE, BILLS + "/" + bill.path("id").asLong(), body);
		assertCode(act(422, Role.PURCHASE, BILLS, bill.path("id").asLong(), "post"), "OVER_BILLING");
	}

	// ---- stock ledger ----

	@Test
	void receiptsAreValuedAtWeightedAverageCost() {
		long warehouse = warehouse();
		long item = item("RAW_MATERIAL");
		long vendor = party("VENDOR", "27");
		receive(item, warehouse, vendor, 100, 10);
		assertAmount(get(Role.OWNER, "/api/masters/items/" + item).path("avgCost"), "10");
		receive(item, warehouse, vendor, 300, 20);
		// (100 x 10 + 300 x 20) / 400
		assertAmount(get(Role.OWNER, "/api/masters/items/" + item).path("avgCost"), "17.5");
	}

	@Test
	void stockIsIssuedOldestBatchFirst() {
		long warehouse = warehouse();
		long item = item("FINISHED", "batchTracked", true);
		adjust(200, item, warehouse, 10, "OLD");
		adjust(200, item, warehouse, 10, "NEW");
		long invoice = post(201, Role.SALES, "/api/sales/docs/sales-invoice",
				docBody(party("CUSTOMER", "27"), warehouse, line(item, 14, 100))).path("id").asLong();
		act(200, Role.SALES, "/api/sales/docs/sales-invoice", invoice, "post");

		JsonNode stock = get(Role.STORE, "/api/inventory/stock?warehouseId=" + warehouse);
		assertThat(stock.size()).isEqualTo(1);
		assertThat(stock.get(0).path("batchNo").asString()).isEqualTo("NEW");
		assertAmount(stock.get(0).path("qty"), "6");
		assertThat(get(Role.STORE, "/api/inventory/movements?itemId=" + item).path("totalElements").asInt()).isEqualTo(4);
	}

	@Test
	void adjustmentsNeedAReasonAndCannotTakeStockBelowZero() {
		long warehouse = warehouse();
		long item = item("RAW_MATERIAL");
		adjust(200, item, warehouse, 10, null);
		adjust(200, item, warehouse, -4, null);
		assertThat(available(item, warehouse)).isEqualByComparingTo("6");

		assertCode(adjust(422, item, warehouse, -7, null), "INSUFFICIENT_STOCK");
		assertCode(adjust(400, item, warehouse, 0, null), "ZERO_ADJUSTMENT");
		assertCode(post(422, Role.STORE, "/api/inventory/adjustments",
				obj("itemId", item, "warehouseId", warehouse, "qtyChange", 1, "reasonCode", "FELT_LIKE_IT")), "UNKNOWN_REASON");
		assertThat(post(400, Role.STORE, "/api/inventory/adjustments", obj("qtyChange", 1)).path("fieldErrors").propertyNames())
				.contains("itemId", "warehouseId", "reasonCode");
		assertCode(adjust(422, item("SERVICE"), warehouse, 5, null), "NOT_A_STOCK_ITEM");
		assertCode(post(403, Role.SALES, "/api/inventory/adjustments",
				obj("itemId", item, "warehouseId", warehouse, "qtyChange", 1, "reasonCode", "COUNT")), "FORBIDDEN");
		assertThat(available(item, warehouse)).isEqualByComparingTo("6");
	}

	@Test
	void transfersMoveStockBetweenWarehouses() {
		long from = warehouse();
		long to = warehouse();
		long item = item("RAW_MATERIAL");
		adjust(200, item, from, 10, null);

		post(200, Role.STORE, "/api/inventory/transfers", obj("itemId", item, "fromWarehouseId", from, "toWarehouseId", to, "qty", 4));
		assertThat(available(item, from)).isEqualByComparingTo("6");
		assertThat(available(item, to)).isEqualByComparingTo("4");

		assertCode(post(422, Role.STORE, "/api/inventory/transfers",
				obj("itemId", item, "fromWarehouseId", from, "toWarehouseId", to, "qty", 7)), "INSUFFICIENT_STOCK");
		assertCode(post(422, Role.STORE, "/api/inventory/transfers",
				obj("itemId", item, "fromWarehouseId", from, "toWarehouseId", from, "qty", 1)), "SAME_WAREHOUSE");
		assertThat(post(400, Role.STORE, "/api/inventory/transfers",
				obj("itemId", item, "fromWarehouseId", from, "toWarehouseId", to, "qty", -1)).path("fieldErrors").has("qty")).isTrue();
		assertThat(available(item, from)).isEqualByComparingTo("6");
	}

	// ---- helpers ----

	/** Orders, approves and receives; returns the posted goods receipt. */
	private JsonNode receive(long item, long warehouse, long vendor, double qty, double rate) {
		long order = post(201, Role.OWNER, ORDERS, docBody(vendor, warehouse, line(item, qty, rate))).path("id").asLong();
		act(200, Role.OWNER, ORDERS, order, "approve");
		long receipt = post(201, Role.OWNER, RECEIPTS + "/from/" + order, null).path("id").asLong();
		return act(200, Role.OWNER, RECEIPTS, receipt, "post");
	}

	private String dueDateFor(long vendor) {
		Map<String, Object> body = docBody(vendor, null, line(item("SERVICE"), 1, 100));
		body.put("referenceNo", unique("V"));
		long bill = post(201, Role.PURCHASE, BILLS, body).path("id").asLong();
		return act(200, Role.PURCHASE, BILLS, bill, "post").path("dueDate").asString();
	}

	private JsonNode pendingInspection(long item) {
		for (JsonNode inspection : get(Role.QUALITY, INSPECTIONS + "?status=PENDING&size=200").path("content")) {
			if (inspection.path("item").path("id").asLong() == item) {
				return inspection;
			}
		}
		throw new AssertionError("No pending inspection for item " + item);
	}

	private JsonNode adjust(int status, long item, long warehouse, double qtyChange, String batch) {
		return post(status, Role.STORE, "/api/inventory/adjustments",
				obj("itemId", item, "warehouseId", warehouse, "batchNo", batch, "qtyChange", qtyChange, "reasonCode", "COUNT"));
	}

	private Map<String, Object> withQty(JsonNode doc, double qty) {
		Map<String, Object> body = bodyOf(doc);
		((List<Map<String, Object>>) body.get("lines")).get(0).put("qty", qty);
		return body;
	}

	private static Map<String, Object> merge(Map<String, Object> base, Object... overrides) {
		base.putAll(obj(overrides));
		return base;
	}
}
