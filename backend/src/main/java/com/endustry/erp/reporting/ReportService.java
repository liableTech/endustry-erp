package com.endustry.erp.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.inventory.StockBalance;
import com.endustry.erp.inventory.StockMovement;
import com.endustry.erp.inventory.StockService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.PermissionService;
import com.endustry.erp.production.ProductionTypes.OutputRepository;
import com.endustry.erp.production.ProductionTypes.WorkOrderRepository;
import com.endustry.erp.production.WorkOrder;
import com.endustry.erp.quality.Inspection;
import com.endustry.erp.quality.QualityApi.InspectionRepository;

import lombok.RequiredArgsConstructor;

/**
 * The report library. Every report returns the same shape (columns plus rows), so one screen
 * renders, filters, totals and exports all of them, and a new report is one method here.
 *
 * Each report draws on one or more modules. It is listed and runs while any of them is switched
 * on for the client, and leaves out the data of the ones that are switched off.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

	public record Definition(String key, String title, String group, String description, boolean dated) {
	}

	/** type is one of text, number, money, date, status. */
	public record Column(String key, String label, String type) {
	}

	public record Report(Definition definition, List<Column> columns, List<Map<String, Object>> rows) {
	}

	private final TradeDocumentRepository documents;
	private final WorkOrderRepository workOrders;
	private final OutputRepository outputs;
	private final InspectionRepository inspections;
	private final StockService stock;
	private final PermissionService permissions;

	private final Map<String, Definition> definitions = new LinkedHashMap<>();
	private final Map<String, List<String>> sources = new LinkedHashMap<>();
	private final Map<String, BiFunction<LocalDate, LocalDate, Report>> runners = new LinkedHashMap<>();

	@jakarta.annotation.PostConstruct
	void register() {
		add("sales-register", "Sales register", "Sales", "Every posted sales invoice with its GST break-up", true, List.of("sales"), this::salesRegister);
		add("order-status", "Sales order status", "Sales", "Ordered, dispatched and pending value per order", true, List.of("sales"), this::orderStatus);
		add("receivables-ageing", "Receivables ageing", "Sales", "What each customer owes, by how overdue it is", false, List.of("sales"), this::receivablesAgeing);
		add("purchase-register", "Purchase register", "Purchase", "Every posted purchase invoice with its GST break-up", true, List.of("purchase"), this::purchaseRegister);
		add("msme-payables", "MSME payables (43B(h))", "Purchase", "Unpaid bills of MSME vendors against the 45-day rule", false, List.of("purchase"), this::msmePayables);
		add("gst-summary", "GST summary", "Compliance", "Output tax, input tax and net payable by month", true, List.of("sales", "purchase"), this::gstSummary);
		add("stock-summary", "Stock summary", "Inventory", "Quantity and value on hand per item", false, List.of("inventory"), this::stockSummary);
		add("production-summary", "Production and cost variance", "Production", "Standard versus actual cost per work order", true, List.of("production"), this::productionSummary);
		add("rejection-analysis", "Rejection analysis", "Quality", "Rejected quantity by reason, from production and incoming inspection", true, List.of("production", "quality"), this::rejectionAnalysis);
	}

	/** The reports this client can run: those drawing on at least one switched-on module. */
	public List<Definition> definitions() {
		return definitions.values().stream().filter(d -> available(d.key())).toList();
	}

	public Report run(String key, LocalDate from, LocalDate to) {
		BiFunction<LocalDate, LocalDate, Report> runner = runners.get(key);
		if (runner == null) {
			throw ApiException.notFound("Report", key);
		}
		if (!available(key)) {
			throw ApiException.forbidden("MODULE_DISABLED", "The '" + definitions.get(key).title()
					+ "' report needs a module that is not part of your subscription");
		}
		LocalDate end = to == null ? LocalDate.now() : to;
		LocalDate start = from == null ? end.minusMonths(6).withDayOfMonth(1) : from;
		if (start.isAfter(end)) {
			throw ApiException.badRequest("INVALID_PERIOD", "The 'from' date cannot be after the 'to' date");
		}
		return runner.apply(start, end);
	}

	private void add(String key, String title, String group, String description, boolean dated, List<String> modules,
			BiFunction<LocalDate, LocalDate, Report> runner) {
		definitions.put(key, new Definition(key, title, group, description, dated));
		sources.put(key, modules);
		runners.put(key, runner);
	}

	private boolean available(String key) {
		return sources.get(key).stream().anyMatch(permissions::moduleEnabled);
	}

	// ---- reports ----

	private Report salesRegister(LocalDate from, LocalDate to) {
		return register("sales-register", "Customer", posted(DocKind.SALES_INVOICE, from, to));
	}

	private Report purchaseRegister(LocalDate from, LocalDate to) {
		return register("purchase-register", "Vendor", posted(DocKind.PURCHASE_INVOICE, from, to));
	}

	private Report register(String key, String partyLabel, List<TradeDocument> docs) {
		List<Map<String, Object>> rows = docs.stream().map(d -> row("docNo", d.getDocNo(), "docDate", d.getDocDate(),
				"party", d.getParty().getName(), "gstin", d.getParty().getGstin(), "referenceNo", d.getReferenceNo(),
				"taxable", d.getTaxableTotal(), "cgst", d.getCgst(), "sgst", d.getSgst(), "igst", d.getIgst(),
				"total", d.getGrandTotal(), "balance", d.getBalance(), "status", d.getStatus())).toList();
		return new Report(definitions.get(key), List.of(col("docNo", "Invoice no", "text"), col("docDate", "Date", "date"),
				col("party", partyLabel, "text"), col("gstin", "GSTIN", "text"), col("referenceNo", "Reference", "text"),
				col("taxable", "Taxable", "money"), col("cgst", "CGST", "money"), col("sgst", "SGST", "money"),
				col("igst", "IGST", "money"), col("total", "Total", "money"), col("balance", "Balance", "money"),
				col("status", "Status", "status")), rows);
	}

	private Report orderStatus(LocalDate from, LocalDate to) {
		List<Map<String, Object>> rows = documents.findByKind(DocKind.SALES_ORDER).stream()
				.filter(d -> d.getStatus() != DocStatus.DRAFT && within(d.getDocDate(), from, to))
				.map(d -> {
					BigDecimal pending = d.getLines().stream().map(l -> l.getPendingQty().multiply(l.getRate()))
							.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
					return row("docNo", d.getDocNo(), "docDate", d.getDocDate(), "party", d.getParty().getName(),
							"referenceNo", d.getReferenceNo(), "ordered", d.getTaxableTotal(),
							"dispatched", d.getTaxableTotal().subtract(pending), "pending", pending, "status", d.getStatus());
				}).toList();
		return new Report(definitions.get("order-status"), List.of(col("docNo", "Order no", "text"), col("docDate", "Date", "date"),
				col("party", "Customer", "text"), col("referenceNo", "Customer PO", "text"), col("ordered", "Ordered", "money"),
				col("dispatched", "Dispatched", "money"), col("pending", "Pending", "money"), col("status", "Status", "status")), rows);
	}

	private Report receivablesAgeing(LocalDate from, LocalDate to) {
		LocalDate today = LocalDate.now();
		Map<String, BigDecimal[]> byParty = new TreeMap<>();
		for (TradeDocument d : documents.findByKindAndStatusIn(DocKind.SALES_INVOICE, List.of(DocStatus.POSTED, DocStatus.PARTLY_PAID))) {
			long daysOverdue = d.getDueDate() == null ? 0 : ChronoUnit.DAYS.between(d.getDueDate(), today);
			int bucket = daysOverdue <= 0 ? 0 : daysOverdue <= 30 ? 1 : daysOverdue <= 60 ? 2 : daysOverdue <= 90 ? 3 : 4;
			BigDecimal[] buckets = byParty.computeIfAbsent(d.getParty().getName(),
					k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
			buckets[bucket] = buckets[bucket].add(d.getBalance());
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		byParty.forEach((party, b) -> rows.add(row("party", party, "notDue", b[0], "d30", b[1], "d60", b[2], "d90", b[3],
				"d90plus", b[4], "total", b[0].add(b[1]).add(b[2]).add(b[3]).add(b[4]))));
		return new Report(definitions.get("receivables-ageing"), List.of(col("party", "Customer", "text"),
				col("notDue", "Not yet due", "money"), col("d30", "1-30 days", "money"), col("d60", "31-60 days", "money"),
				col("d90", "61-90 days", "money"), col("d90plus", "Over 90 days", "money"), col("total", "Total due", "money")), rows);
	}

	private Report msmePayables(LocalDate from, LocalDate to) {
		LocalDate today = LocalDate.now();
		List<Map<String, Object>> rows = documents
				.findByKindAndStatusIn(DocKind.PURCHASE_INVOICE, List.of(DocStatus.POSTED, DocStatus.PARTLY_PAID)).stream()
				.filter(d -> d.getParty().isMsmeRegistered())
				.sorted((a, b) -> a.getDueDate().compareTo(b.getDueDate()))
				.map(d -> {
					long days = ChronoUnit.DAYS.between(today, d.getDueDate());
					return row("party", d.getParty().getName(), "udyam", d.getParty().getUdyamNumber(), "docNo", d.getDocNo(),
							"referenceNo", d.getReferenceNo(), "docDate", d.getDocDate(), "dueDate", d.getDueDate(),
							"daysLeft", days, "balance", d.getBalance(),
							"risk", days < 0 ? "OVERDUE" : days <= 7 ? "DUE_SOON" : "ON_TRACK");
				}).toList();
		return new Report(definitions.get("msme-payables"), List.of(col("party", "MSME vendor", "text"), col("udyam", "Udyam no", "text"),
				col("docNo", "Our ref", "text"), col("referenceNo", "Vendor invoice", "text"), col("docDate", "Invoice date", "date"),
				col("dueDate", "Pay by", "date"), col("daysLeft", "Days left", "number"), col("balance", "Balance", "money"),
				col("risk", "Risk", "status")), rows);
	}

	private Report gstSummary(LocalDate from, LocalDate to) {
		Map<YearMonth, BigDecimal[]> byMonth = new TreeMap<>();
		posted(DocKind.SALES_INVOICE, from, to).forEach(d -> {
			BigDecimal[] m = byMonth.computeIfAbsent(YearMonth.from(d.getDocDate()), k -> zeros(4));
			m[0] = m[0].add(d.getTaxableTotal());
			m[1] = m[1].add(d.getTaxTotal());
		});
		posted(DocKind.PURCHASE_INVOICE, from, to).forEach(d -> {
			BigDecimal[] m = byMonth.computeIfAbsent(YearMonth.from(d.getDocDate()), k -> zeros(4));
			m[2] = m[2].add(d.getTaxableTotal());
			m[3] = m[3].add(d.getTaxTotal());
		});
		List<Map<String, Object>> rows = new ArrayList<>();
		byMonth.forEach((month, m) -> rows.add(row("month", month.toString(), "sales", m[0], "outputTax", m[1],
				"purchases", m[2], "inputTax", m[3], "net", m[1].subtract(m[3]))));
		return new Report(definitions.get("gst-summary"), List.of(col("month", "Month", "text"), col("sales", "Taxable sales", "money"),
				col("outputTax", "Output GST", "money"), col("purchases", "Taxable purchases", "money"),
				col("inputTax", "Input GST", "money"), col("net", "Net payable", "money")), rows);
	}

	private Report stockSummary(LocalDate from, LocalDate to) {
		Map<String, Map<String, Object>> byItem = new LinkedHashMap<>();
		for (StockBalance b : stock.balances()) {
			Map<String, Object> r = byItem.computeIfAbsent(b.itemCode(), k -> row("itemCode", b.itemCode(), "itemName", b.itemName(),
					"category", b.category(), "uom", b.uom(), "available", BigDecimal.ZERO, "inQc", BigDecimal.ZERO,
					"rejected", BigDecimal.ZERO, "avgCost", b.avgCost(), "value", BigDecimal.ZERO, "reorderLevel", b.reorderLevel()));
			String bucket = b.status() == StockMovement.Status.AVAILABLE ? "available"
					: b.status() == StockMovement.Status.QC_PENDING ? "inQc" : "rejected";
			r.merge(bucket, b.qty(), (x, y) -> ((BigDecimal) x).add((BigDecimal) y));
			if (b.status() != StockMovement.Status.REJECTED) {
				r.merge("value", b.value().setScale(2, RoundingMode.HALF_UP), (x, y) -> ((BigDecimal) x).add((BigDecimal) y));
			}
		}
		return new Report(definitions.get("stock-summary"), List.of(col("itemCode", "Item code", "text"), col("itemName", "Item", "text"),
				col("category", "Category", "text"), col("uom", "Unit", "text"), col("available", "Available", "number"),
				col("inQc", "In QC", "number"), col("rejected", "Rejected", "number"), col("avgCost", "Avg cost", "money"),
				col("value", "Value", "money"), col("reorderLevel", "Reorder level", "number")), new ArrayList<>(byItem.values()));
	}

	private Report productionSummary(LocalDate from, LocalDate to) {
		List<Map<String, Object>> rows = workOrders.findAll().stream()
				.filter(w -> w.getStatus() != WorkOrder.Status.CANCELLED && within(w.getStartDate(), from, to))
				.map(w -> row("woNo", w.getWoNo(), "item", w.getItem().getName(), "startDate", w.getStartDate(),
						"dueDate", w.getDueDate(), "planned", w.getPlannedQty(), "produced", w.getProducedQty(),
						"rejected", w.getRejectedQty(), "standardCost", w.getStandardUnitCost(),
						"actualCost", w.getActualUnitCost(),
						"variance", w.getProducedQty().signum() == 0 ? BigDecimal.ZERO : w.getActualUnitCost().subtract(w.getStandardUnitCost()),
						"status", w.getStatus()))
				.toList();
		return new Report(definitions.get("production-summary"), List.of(col("woNo", "Work order", "text"), col("item", "Item", "text"),
				col("startDate", "Start", "date"), col("dueDate", "Due", "date"), col("planned", "Planned", "number"),
				col("produced", "Good", "number"), col("rejected", "Rejected", "number"), col("standardCost", "Std cost / unit", "money"),
				col("actualCost", "Actual cost / unit", "money"), col("variance", "Variance / unit", "money"),
				col("status", "Status", "status")), rows);
	}

	private Report rejectionAnalysis(LocalDate from, LocalDate to) {
		Map<String, BigDecimal[]> byReason = new TreeMap<>();
		(permissions.moduleEnabled("production") ? outputs.findAll() : List.<WorkOrder.Output>of()).stream().filter(o -> o.getReasonCode() != null && within(o.getOutputDate(), from, to))
				.forEach(o -> addTo(byReason, o.getReasonCode(), 0, o.getRejectedQty()));
		(permissions.moduleEnabled("quality") ? inspections.findByStatus(Inspection.Status.COMPLETED) : List.<Inspection>of()).stream().filter(i -> i.getReasonCode() != null)
				.forEach(i -> addTo(byReason, i.getReasonCode(), 1, i.getRejectedQty()));
		List<Map<String, Object>> rows = new ArrayList<>();
		byReason.forEach((reason, q) -> rows.add(row("reason", reason, "production", q[0], "incoming", q[1], "total", q[0].add(q[1]))));
		return new Report(definitions.get("rejection-analysis"), List.of(col("reason", "Reason", "text"),
				col("production", "In production", "number"), col("incoming", "At incoming inspection", "number"),
				col("total", "Total rejected", "number")), rows);
	}

	// ---- helpers ----

	/** Posted invoices of a kind; none when the kind's module is switched off for the client. */
	private List<TradeDocument> posted(DocKind kind, LocalDate from, LocalDate to) {
		if (!permissions.moduleEnabled(kind.module())) {
			return List.of();
		}
		return documents.findByKindAndStatusIn(kind, List.of(DocStatus.POSTED, DocStatus.PARTLY_PAID, DocStatus.PAID)).stream()
				.filter(d -> within(d.getDocDate(), from, to)).sorted((a, b) -> a.getDocDate().compareTo(b.getDocDate())).toList();
	}

	private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
		return !date.isBefore(from) && !date.isAfter(to);
	}

	private static void addTo(Map<String, BigDecimal[]> totals, String key, int index, BigDecimal value) {
		BigDecimal[] values = totals.computeIfAbsent(key, k -> zeros(2));
		values[index] = values[index].add(value);
	}

	private static BigDecimal[] zeros(int n) {
		BigDecimal[] values = new BigDecimal[n];
		java.util.Arrays.fill(values, BigDecimal.ZERO);
		return values;
	}

	private static Column col(String key, String label, String type) {
		return new Column(key, label, type);
	}

	private static Map<String, Object> row(Object... keysAndValues) {
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			row.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return row;
	}
}
