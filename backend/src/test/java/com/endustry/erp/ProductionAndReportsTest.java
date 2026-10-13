package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** Plan to produce (BRD 9.3): BOM, work order, shop floor, costing; plus dashboard and reports. */
class ProductionAndReportsTest extends ApiTest {

	private static final String BOMS = "/api/production/boms";
	private static final String WORK_ORDERS = "/api/production/work-orders";

	// ---- BOM ----

	@Test
	void aBomCostsMaterialWithScrapPlusOperations() {
		long steel = item("RAW_MATERIAL", "purchasePrice", 100);
		long seal = item("COMPONENT", "purchasePrice", 20);
		long shaft = item("FINISHED");
		long lathe = workCentre(600);

		JsonNode bom = post(201, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 1,
				"lines", List.of(obj("componentId", steel, "qty", 2, "scrapPct", 5), obj("componentId", seal, "qty", 1)),
				"operations", List.of(obj("name", "Turning", "workCentreId", lathe, "setupMinutes", 30, "runMinutesPerUnit", 10))));
		assertThat(bom.path("status").asString()).isEqualTo("DRAFT");
		assertThat(bom.path("versionNo").asInt()).isEqualTo(1);

		// Material 2 x 1.05 x 100 + 20 = 230; operation (30 + 10) min at 600/h = 400.
		JsonNode cost = get(Role.PRODUCTION, BOMS + "/" + bom.path("id").asLong() + "/cost");
		assertAmount(cost.path("materialCost"), "230");
		assertAmount(cost.path("operationCost"), "400");
		assertAmount(cost.path("unitCost"), "630");

		assertThat(get(Role.PRODUCTION, BOMS + "/where-used/" + steel).size()).isEqualTo(1);
		assertThat(get(Role.PRODUCTION, BOMS + "/where-used/" + shaft).size()).isZero();
	}

	@Test
	void aBomCannotContainItselfDirectlyOrIndirectly() {
		long steel = item("RAW_MATERIAL");
		long sub = item("SEMI_FINISHED");
		long top = item("FINISHED");
		assertCode(post(422, Role.PRODUCTION, BOMS, bomBody(top, top, 1)), "CIRCULAR_BOM");

		approvedBom(sub, steel, 1);
		approvedBom(top, sub, 1);
		// sub is made from steel, top from sub; making sub from top would loop.
		assertCode(post(422, Role.PRODUCTION, BOMS, bomBody(sub, top, 1)), "CIRCULAR_BOM");
	}

	@Test
	void rejectsABomWithBadContents() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		assertCode(post(422, Role.PRODUCTION, BOMS, bomBody(steel, steel, 1)), "BOM_ITEM_NOT_MANUFACTURED");
		assertCode(post(422, Role.PRODUCTION, BOMS, bomBody(shaft, item("SERVICE"), 1)), "NOT_A_STOCK_ITEM");
		assertCode(post(422, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 1,
				"lines", List.of(obj("componentId", steel, "qty", 1), obj("componentId", steel, "qty", 2)))), "DUPLICATE_COMPONENT");
		assertCode(post(422, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 1, "lines", List.of(obj("componentId", steel, "qty", 1)),
				"operations", List.of(obj("name", "Turning", "workCentreId", 99999999, "runMinutesPerUnit", 1)))), "UNKNOWN_WORK_CENTRE");

		JsonNode invalid = post(400, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 0,
				"lines", List.of(obj("componentId", steel, "qty", -1, "scrapPct", 80))));
		assertThat(invalid.path("fieldErrors").propertyNames()).contains("outputQty", "lines[0].qty", "lines[0].scrapPct");
		assertThat(post(400, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 1, "lines", List.of()))
				.path("fieldErrors").path("lines").asString()).contains("at least one component");
		assertCode(post(403, Role.SALES, BOMS, bomBody(shaft, steel, 1)), "FORBIDDEN");
	}

	@Test
	void anApprovedBomIsFrozenAndANewVersionReplacesIt() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		long v1 = approvedBom(shaft, steel, 2);

		assertCode(put(409, Role.PRODUCTION, BOMS + "/" + v1, bomBody(shaft, steel, 3)), "NOT_EDITABLE");
		assertCode(post(409, Role.PRODUCTION, BOMS + "/" + v1 + "/approve", null), "INVALID_TRANSITION");

		JsonNode v2 = post(201, Role.PRODUCTION, BOMS + "/" + v1 + "/copy", null);
		assertThat(v2.path("versionNo").asInt()).isEqualTo(2);
		assertThat(v2.path("status").asString()).isEqualTo("DRAFT");
		assertCode(put(422, Role.PRODUCTION, BOMS + "/" + v2.path("id").asLong(), bomBody(item("FINISHED"), steel, 3)), "BOM_ITEM_LOCKED");
		put(200, Role.PRODUCTION, BOMS + "/" + v2.path("id").asLong(), bomBody(shaft, steel, 3));
		post(200, Role.PRODUCTION, BOMS + "/" + v2.path("id").asLong() + "/approve", null);

		assertThat(get(Role.PRODUCTION, BOMS + "/" + v1).path("status").asString()).isEqualTo("OBSOLETE");
		// New work orders pick up version 2 automatically.
		JsonNode wo = post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse()));
		assertThat(wo.path("bom").path("versionNo").asInt()).isEqualTo(2);
		// The retired version can no longer be chosen.
		Map<String, Object> old = workOrderBody(shaft, 10, warehouse());
		old.put("bomId", v1);
		assertCode(post(422, Role.PRODUCTION, WORK_ORDERS, old), "BOM_NOT_APPROVED");
	}

	// ---- work orders ----

	@Test
	void aWorkOrderNeedsAnApprovedBomAndSensibleDates() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		long warehouse = warehouse();
		assertCode(post(422, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse)), "NO_APPROVED_BOM");

		long draft = post(201, Role.PRODUCTION, BOMS, bomBody(shaft, steel, 1)).path("id").asLong();
		assertCode(post(422, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse)), "NO_APPROVED_BOM");
		post(200, Role.PRODUCTION, BOMS + "/" + draft + "/approve", null);

		Map<String, Object> otherItem = workOrderBody(item("FINISHED"), 10, warehouse);
		otherItem.put("bomId", draft);
		assertCode(post(422, Role.PRODUCTION, WORK_ORDERS, otherItem), "BOM_ITEM_MISMATCH");

		Map<String, Object> late = workOrderBody(shaft, 10, warehouse);
		late.put("dueDate", TODAY.minusDays(1).toString());
		assertCode(post(422, Role.PRODUCTION, WORK_ORDERS, late), "DUE_BEFORE_START");

		JsonNode invalid = post(400, Role.PRODUCTION, WORK_ORDERS, obj("itemId", shaft, "plannedQty", 0));
		assertThat(invalid.path("fieldErrors").propertyNames()).contains("plannedQty", "warehouseId", "dueDate");
		assertThat(post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse)).path("woNo").asString()).startsWith("WO/");
	}

	@Test
	void releaseIsHeldBackByAMaterialShortageUnlessForced() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		long warehouse = warehouse();
		approvedBom(shaft, steel, 2);
		stockIn(steel, warehouse, 15);
		long wo = post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse)).path("id").asLong();

		JsonNode need = get(Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/availability").get(0);
		assertAmount(need.path("required"), "20");
		assertAmount(need.path("available"), "15");
		assertAmount(need.path("shortage"), "5");

		JsonNode blocked = post(422, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/release", null);
		assertCode(blocked, "MATERIAL_SHORTAGE");
		assertAmount(blocked.path("details").get(0).path("shortage"), "5");
		assertThat(post(200, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/release?force=true", null).path("status").asString()).isEqualTo("RELEASED");
		assertCode(post(409, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/release", null), "INVALID_TRANSITION");

		// With only 15 on hand, 8 units (16 needed) cannot be booked; 7 can.
		assertCode(output(422, wo, 8, 0, null), "INSUFFICIENT_STOCK");
		assertThat(available(steel, warehouse)).isEqualByComparingTo("15");
		output(200, wo, 7, 0, null);
		assertThat(available(steel, warehouse)).isEqualByComparingTo("1");
	}

	@Test
	void outputConsumesComponentsReceivesGoodsAndCostsTheOrder() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED", "batchTracked", true);
		long warehouse = warehouse();
		long lathe = workCentre(600);
		long bom = post(201, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 1,
				"lines", List.of(obj("componentId", steel, "qty", 2, "scrapPct", 5)),
				"operations", List.of(obj("name", "Turning", "workCentreId", lathe, "setupMinutes", 30, "runMinutesPerUnit", 6)))).path("id").asLong();
		// Opening stock comes in at the purchase price of 100, which becomes the average cost.
		stockIn(steel, warehouse, 100);
		post(200, Role.PRODUCTION, BOMS + "/" + bom + "/approve", null);

		JsonNode created = post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse));
		long wo = created.path("id").asLong();
		// Standard for a run of 10: 2.1 x 100 material, plus (30 setup + 60 run) min at 600/h spread over 10 units.
		assertAmount(created.path("standardUnitCost"), "300");
		assertCode(output(409, wo, 1, 0, null), "INVALID_TRANSITION");
		post(200, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/release", null);

		// First booking: 6 good + 2 rejected. Material 8 x 2.1 x 100 = 1680; operation (30 + 48) min = 780.
		JsonNode partDone = output(200, wo, 6, 2, "DIMENSION");
		assertThat(partDone.path("status").asString()).isEqualTo("IN_PROGRESS");
		assertAmount(partDone.path("materialCost"), "1680");
		assertAmount(partDone.path("operationCost"), "780");
		assertAmount(partDone.path("remainingQty"), "2");
		assertAmount(partDone.path("actualUnitCost"), "410");
		assertThat(available(steel, warehouse)).isEqualByComparingTo("83.2");
		assertThat(available(shaft, warehouse)).isEqualByComparingTo("6");

		// Second booking closes the order; setup is not charged twice.
		JsonNode done = output(200, wo, 2, 0, null);
		assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
		assertAmount(done.path("producedQty"), "8");
		assertAmount(done.path("rejectedQty"), "2");
		assertAmount(done.path("operationCost"), "900");
		assertThat(done.path("completedDate").asString()).isEqualTo(TODAY.toString());
		assertThat(get(Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/outputs").size()).isEqualTo(2);
		assertCode(output(409, wo, 1, 0, null), "INVALID_TRANSITION");

		// Finished goods carry the work order number as their batch.
		JsonNode stock = get(Role.STORE, "/api/inventory/stock?warehouseId=" + warehouse + "&q=" + created.path("woNo").asString());
		assertAmount(stock.get(0).path("qty"), "8");
	}

	@Test
	void outputCannotExceedThePlanAndRejectsNeedAReason() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		long warehouse = warehouse();
		approvedBom(shaft, steel, 1);
		stockIn(steel, warehouse, 100);
		long wo = post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse)).path("id").asLong();
		post(200, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/release", null);

		assertCode(output(422, wo, 9, 2, "DIMENSION"), "OVER_PRODUCTION");
		assertCode(output(422, wo, 5, 1, null), "REASON_REQUIRED");
		assertCode(output(422, wo, 5, 1, "NOT_A_REASON"), "REASON_REQUIRED");
		assertCode(output(400, wo, 0, 0, null), "ZERO_OUTPUT");
		assertThat(post(400, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/output", obj("goodQty", -1)).path("fieldErrors").propertyNames())
				.contains("goodQty", "rejectedQty");
		assertCode(post(422, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/output",
				obj("goodQty", 1, "rejectedQty", 0, "outputDate", TODAY.plusDays(1).toString())), "FUTURE_DATE");
		assertThat(available(steel, warehouse)).isEqualByComparingTo("100");

		// Once output is booked the order can no longer be cancelled.
		output(200, wo, 1, 0, null);
		assertCode(post(409, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/cancel", null), "INVALID_TRANSITION");
	}

	@Test
	void jobCardsRunInRoutingOrderAndCaptureTime() {
		long steel = item("RAW_MATERIAL");
		long shaft = item("FINISHED");
		long warehouse = warehouse();
		long lathe = workCentre(300);
		long bom = post(201, Role.PRODUCTION, BOMS, obj("itemId", shaft, "outputQty", 1, "lines", List.of(obj("componentId", steel, "qty", 1)),
				"operations", List.of(obj("name", "Turning", "workCentreId", lathe, "runMinutesPerUnit", 5),
						obj("name", "Grinding", "workCentreId", lathe, "runMinutesPerUnit", 3)))).path("id").asLong();
		post(200, Role.PRODUCTION, BOMS + "/" + bom + "/approve", null);
		long wo = post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 10, warehouse)).path("id").asLong();
		assertThat(get(Role.PRODUCTION, WORK_ORDERS + "/" + wo).path("jobCards").size()).isZero();

		JsonNode released = post(200, Role.PRODUCTION, WORK_ORDERS + "/" + wo + "/release?force=true", null);
		long turning = released.path("jobCards").get(0).path("id").asLong();
		long grinding = released.path("jobCards").get(1).path("id").asLong();
		String cards = "/api/production/job-cards/";

		assertCode(post(422, Role.SUPERVISOR, cards + grinding + "/start", null), "OPERATION_OUT_OF_SEQUENCE");
		assertCode(post(409, Role.SUPERVISOR, cards + turning + "/pause", obj("reasonCode", "NO_POWER")), "INVALID_TRANSITION");

		JsonNode running = post(200, Role.SUPERVISOR, cards + turning + "/start", null);
		assertThat(running.path("status").asString()).isEqualTo("IN_PROGRESS");
		assertThat(running.path("jobCards").get(0).path("status").asString()).isEqualTo("RUNNING");
		assertCode(post(409, Role.SUPERVISOR, cards + turning + "/start", null), "INVALID_TRANSITION");
		assertCode(post(422, Role.SUPERVISOR, cards + turning + "/pause", null), "REASON_REQUIRED");

		JsonNode paused = post(200, Role.SUPERVISOR, cards + turning + "/pause", obj("reasonCode", "NO_POWER"));
		assertThat(paused.path("jobCards").get(0).path("pauseReason").asString()).isEqualTo("NO_POWER");
		assertThat(paused.path("jobCards").get(0).path("minutesWorked").asInt()).isGreaterThanOrEqualTo(1);

		post(200, Role.SUPERVISOR, cards + turning + "/start", null);
		assertCode(post(422, Role.SUPERVISOR, cards + turning + "/complete", obj("goodQty", 11, "rejectedQty", 0)), "OVER_PRODUCTION");
		JsonNode done = post(200, Role.SUPERVISOR, cards + turning + "/complete", obj("goodQty", 9, "rejectedQty", 1));
		assertThat(done.path("jobCards").get(0).path("status").asString()).isEqualTo("DONE");
		post(200, Role.SUPERVISOR, cards + grinding + "/start", null);

		assertCode(post(400, Role.SUPERVISOR, cards + grinding + "/dance", null), "UNKNOWN_ACTION");
		assertCode(post(404, Role.SUPERVISOR, cards + "99999999/start", null), "NOT_FOUND");
		assertCode(post(403, Role.STORE, cards + grinding + "/complete", null), "FORBIDDEN");
	}

	@Test
	void theMaterialPlanSuggestsWhatToBuy() {
		long steel = item("RAW_MATERIAL", "reorderLevel", 50, "leadTimeDays", 7);
		long shaft = item("FINISHED");
		long warehouse = warehouse();
		long vendor = party("VENDOR", "27");
		approvedBom(shaft, steel, 2);
		stockIn(steel, warehouse, 30);
		post(201, Role.PRODUCTION, WORK_ORDERS, workOrderBody(shaft, 100, warehouse));

		// Need 200 for the order, keep 50 as reorder level, have 30: buy 220.
		assertAmount(planLine(steel).path("suggestedQty"), "220");
		assertAmount(planLine(steel).path("required"), "200");

		// 150 on an approved purchase order reduces the suggestion to 70.
		long po = post(201, Role.PURCHASE, "/api/purchase/docs/purchase-order", docBody(vendor, warehouse, line(steel, 150, 10))).path("id").asLong();
		assertAmount(planLine(steel).path("suggestedQty"), "220");
		act(200, Role.PURCHASE, "/api/purchase/docs/purchase-order", po, "approve");
		assertAmount(planLine(steel).path("onOrder"), "150");
		assertAmount(planLine(steel).path("suggestedQty"), "70");
	}

	// ---- dashboard and reports ----

	@Test
	void theDashboardAndEveryReportRun() {
		long warehouse = warehouse();
		long item = item("FINISHED");
		stockIn(item, warehouse, 10);
		long customer = party("CUSTOMER", "27");
		long invoice = post(201, Role.SALES, "/api/sales/docs/sales-invoice", docBody(customer, warehouse, line(item, 2, 500))).path("id").asLong();
		String invoiceNo = act(200, Role.SALES, "/api/sales/docs/sales-invoice", invoice, "post").path("docNo").asString();

		JsonNode summary = get(Role.OWNER, "/api/dashboard/summary");
		assertThat(summary.path("kpis").path("salesToday").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("1000"));
		assertThat(summary.path("kpis").path("receivables").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("1180"));
		assertThat(summary.path("trend").size()).isEqualTo(6);
		assertThat(summary.path("recentDocuments").size()).isBetween(1, 8);

		JsonNode definitions = get(Role.CA, "/api/reports");
		assertThat(definitions.size()).isGreaterThanOrEqualTo(9);
		for (JsonNode definition : definitions) {
			JsonNode report = get(Role.CA, "/api/reports/" + definition.path("key").asString());
			assertThat(report.path("columns").size()).as(definition.path("key").asString()).isGreaterThan(2);
			assertThat(report.path("rows").isArray()).isTrue();
		}
		assertThat(get(Role.CA, "/api/reports/sales-register").path("rows").toString()).contains(invoiceNo);
		assertThat(get(Role.CA, "/api/reports/sales-register?from=" + TODAY.plusDays(1) + "&to=" + TODAY.plusDays(2)).path("rows").size()).isZero();

		assertCode(call(404, Role.CA, HttpMethod.GET, "/api/reports/horoscope", null), "NOT_FOUND");
		assertCode(call(400, Role.CA, HttpMethod.GET, "/api/reports/sales-register?from=2026-05-01&to=2026-04-01", null), "INVALID_PERIOD");
		assertCode(call(400, Role.CA, HttpMethod.GET, "/api/reports/sales-register?from=yesterday", null), "MALFORMED_REQUEST");
		assertCode(call(403, Role.STORE, HttpMethod.GET, "/api/reports/sales-register", null), "FORBIDDEN");
	}

	// ---- helpers ----

	private Map<String, Object> bomBody(long item, long component, double qty) {
		return obj("itemId", item, "outputQty", 1, "lines", List.of(obj("componentId", component, "qty", qty)));
	}

	private long approvedBom(long item, long component, double qty) {
		long id = post(201, Role.PRODUCTION, BOMS, bomBody(item, component, qty)).path("id").asLong();
		post(200, Role.PRODUCTION, BOMS + "/" + id + "/approve", null);
		return id;
	}

	private Map<String, Object> workOrderBody(long item, double qty, long warehouse) {
		return obj("itemId", item, "plannedQty", qty, "warehouseId", warehouse, "dueDate", TODAY.plusDays(7).toString());
	}

	private JsonNode output(int status, long workOrder, double good, double rejected, String reason) {
		return post(status, Role.PRODUCTION, WORK_ORDERS + "/" + workOrder + "/output",
				obj("goodQty", good, "rejectedQty", rejected, "reasonCode", reason));
	}

	private JsonNode planLine(long item) {
		for (JsonNode line : get(Role.PRODUCTION, "/api/production/material-plan")) {
			if (line.path("itemId").asLong() == item) {
				return line;
			}
		}
		throw new AssertionError("Item " + item + " is not on the material plan");
	}
}
