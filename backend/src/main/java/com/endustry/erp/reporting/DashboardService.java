package com.endustry.erp.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.inventory.StockBalance;
import com.endustry.erp.inventory.StockMovement;
import com.endustry.erp.inventory.StockService;
import com.endustry.erp.platform.security.PermissionService;
import com.endustry.erp.production.ProductionTypes.OutputRepository;
import com.endustry.erp.production.ProductionTypes.WorkOrderRepository;
import com.endustry.erp.production.WorkOrder;
import com.endustry.erp.quality.Inspection;
import com.endustry.erp.quality.QualityApi.InspectionRepository;

import lombok.RequiredArgsConstructor;

/**
 * RPT-01: the owner's single view of sales, production, stock, cash and what needs attention.
 * Only the modules switched on for the client feed it: a switched-off module's figures come back
 * empty (null for its KPIs), so nothing of it shows or leaks through the dashboard.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

	private static final int TREND_MONTHS = 6;
	private static final int MSME_WARNING_DAYS = 7;
	private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

	private final TradeDocumentRepository documents;
	private final WorkOrderRepository workOrders;
	private final OutputRepository outputs;
	private final InspectionRepository inspections;
	private final StockService stock;
	private final PermissionService permissions;

	public record Kpis(BigDecimal salesToday, BigDecimal salesThisMonth, BigDecimal salesLastMonth, BigDecimal openOrdersValue,
			Long openOrdersCount, BigDecimal receivables, BigDecimal overdueReceivables, BigDecimal payables,
			BigDecimal msmeAtRisk, BigDecimal stockValue, Long lowStockCount, Long openWorkOrders, BigDecimal plannedQty,
			BigDecimal producedQty, BigDecimal rejectionPct, Long pendingInspections) {
	}

	public record TrendPoint(String label, BigDecimal sales, BigDecimal purchases, BigDecimal produced, BigDecimal rejected) {
	}

	public record NameValue(String name, BigDecimal value) {
	}

	public record Alert(String severity, String title, String detail, String link) {
	}

	public record RecentDocument(Long id, String kind, String docNo, LocalDate docDate, String party, BigDecimal total, DocStatus status) {
	}

	/** modules lists the switched-on modules the figures were drawn from. */
	public record Summary(List<String> modules, Kpis kpis, List<TrendPoint> trend, List<NameValue> topCustomers, List<NameValue> stockByCategory,
			List<NameValue> workOrdersByStatus, List<Alert> alerts, List<RecentDocument> recentDocuments) {
	}

	public Summary summary() {
		LocalDate today = LocalDate.now();
		YearMonth thisMonth = YearMonth.from(today);
		boolean salesOn = on("sales");
		boolean purchaseOn = on("purchase");
		boolean inventoryOn = on("inventory");
		boolean productionOn = on("production");
		boolean qualityOn = on("quality");
		List<TradeDocument> sales = salesOn ? posted(DocKind.SALES_INVOICE) : List.of();
		List<TradeDocument> purchases = purchaseOn ? posted(DocKind.PURCHASE_INVOICE) : List.of();
		List<TradeDocument> openOrders = salesOn ? documents.findByKindAndStatusIn(DocKind.SALES_ORDER,
				List.of(DocStatus.CONFIRMED, DocStatus.PARTLY_FULFILLED)) : List.of();
		List<WorkOrder> allWorkOrders = productionOn ? workOrders.findAll() : List.of();
		List<WorkOrder.Output> allOutputs = productionOn ? outputs.findAll() : List.of();
		List<StockBalance> balances = inventoryOn ? stock.balances() : List.of();

		List<TradeDocument> receivable = sales.stream().filter(d -> d.getStatus().isOutstanding()).toList();
		List<TradeDocument> payable = purchases.stream().filter(d -> d.getStatus().isOutstanding()).toList();
		List<TradeDocument> overdue = receivable.stream().filter(d -> d.getDueDate() != null && d.getDueDate().isBefore(today)).toList();
		List<TradeDocument> msme = payable.stream().filter(d -> d.getParty().isMsmeRegistered()
				&& d.getDueDate() != null && !d.getDueDate().isAfter(today.plusDays(MSME_WARNING_DAYS))).toList();

		// Low stock: available quantity across warehouses below the item's reorder level.
		Map<String, BigDecimal> availableByItem = new LinkedHashMap<>();
		Map<String, StockBalance> sample = new LinkedHashMap<>();
		for (StockBalance b : balances) {
			sample.putIfAbsent(b.itemCode(), b);
			if (b.status() == StockMovement.Status.AVAILABLE) {
				availableByItem.merge(b.itemCode(), b.qty(), BigDecimal::add);
			}
		}
		List<StockBalance> low = sample.values().stream().filter(b -> b.reorderLevel().signum() > 0
				&& availableByItem.getOrDefault(b.itemCode(), BigDecimal.ZERO).compareTo(b.reorderLevel()) < 0).toList();

		List<WorkOrder> open = allWorkOrders.stream().filter(w -> w.getStatus().isOpen()).toList();
		BigDecimal good = sum(allOutputs, o -> true, WorkOrder.Output::getGoodQty);
		BigDecimal rejected = sum(allOutputs, o -> true, WorkOrder.Output::getRejectedQty);
		long pendingInspections = qualityOn ? inspections.findByStatus(Inspection.Status.PENDING).size() : 0;

		Kpis kpis = new Kpis(
				when(salesOn, sum(sales, d -> d.getDocDate().equals(today), TradeDocument::getTaxableTotal)),
				when(salesOn, sum(sales, d -> YearMonth.from(d.getDocDate()).equals(thisMonth), TradeDocument::getTaxableTotal)),
				when(salesOn, sum(sales, d -> YearMonth.from(d.getDocDate()).equals(thisMonth.minusMonths(1)), TradeDocument::getTaxableTotal)),
				when(salesOn, openOrders.stream().flatMap(d -> d.getLines().stream())
						.map(l -> l.getPendingQty().multiply(l.getRate())).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)),
				when(salesOn, (long) openOrders.size()),
				when(salesOn, sum(receivable, d -> true, TradeDocument::getBalance)),
				when(salesOn, sum(overdue, d -> true, TradeDocument::getBalance)),
				when(purchaseOn, sum(payable, d -> true, TradeDocument::getBalance)),
				when(purchaseOn, sum(msme, d -> true, TradeDocument::getBalance)),
				when(inventoryOn, balances.stream().filter(b -> b.status() != StockMovement.Status.REJECTED).map(StockBalance::value)
						.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)),
				when(inventoryOn, (long) low.size()),
				when(productionOn, (long) open.size()),
				when(productionOn, sum(open, w -> true, WorkOrder::getPlannedQty)),
				when(productionOn, sum(open, w -> true, WorkOrder::getProducedQty)),
				when(productionOn, percent(rejected, good.add(rejected))),
				when(qualityOn, pendingInspections));

		List<TrendPoint> trend = new ArrayList<>();
		for (int i = TREND_MONTHS - 1; i >= 0; i--) {
			YearMonth month = thisMonth.minusMonths(i);
			trend.add(new TrendPoint(month.format(MONTH_LABEL),
					sum(sales, d -> YearMonth.from(d.getDocDate()).equals(month), TradeDocument::getTaxableTotal),
					sum(purchases, d -> YearMonth.from(d.getDocDate()).equals(month), TradeDocument::getTaxableTotal),
					sum(allOutputs, o -> YearMonth.from(o.getOutputDate()).equals(month), WorkOrder.Output::getGoodQty),
					sum(allOutputs, o -> YearMonth.from(o.getOutputDate()).equals(month), WorkOrder.Output::getRejectedQty)));
		}

		List<Alert> alerts = new ArrayList<>();
		if (!overdue.isEmpty()) {
			alerts.add(new Alert("danger", overdue.size() + " invoice(s) overdue from customers",
					"Collect " + sum(overdue, d -> true, TradeDocument::getBalance).toPlainString() + " that is past its due date", "/sales/invoices"));
		}
		if (!msme.isEmpty()) {
			alerts.add(new Alert("warning", msme.size() + " MSME vendor payment(s) due within " + MSME_WARNING_DAYS + " days",
					"Paying late disallows the expense under Section 43B(h) and attracts interest",
					on("reports") ? "/reports/msme-payables" : "/purchase/invoices"));
		}
		if (!low.isEmpty()) {
			alerts.add(new Alert("warning", low.size() + " item(s) below reorder level",
					low.stream().limit(4).map(StockBalance::itemCode).collect(Collectors.joining(", ")), productionOn ? "/production/material-plan" : "/inventory/stock"));
		}
		if (pendingInspections > 0) {
			alerts.add(new Alert("info", pendingInspections + " received batch(es) waiting for inspection",
					"Stock stays unusable until quality releases it", "/quality/inspections"));
		}
		long late = open.stream().filter(w -> w.getDueDate().isBefore(today)).count();
		if (late > 0) {
			alerts.add(new Alert("danger", late + " work order(s) past their due date", "Review the production schedule", "/production/work-orders"));
		}

		List<DocKind> visibleKinds = Arrays.stream(DocKind.values()).filter(k -> on(k.module())).toList();
		List<RecentDocument> recent = (visibleKinds.isEmpty() ? List.<TradeDocument>of()
				: documents.findByKindIn(visibleKinds, PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "id")))).stream()
				.map(d -> new RecentDocument(d.getId(), d.getKind().slug(), d.getDocNo(), d.getDocDate(), d.getParty().getName(),
						d.getGrandTotal(), d.getStatus()))
				.toList();

		List<String> modules = PermissionService.MODULES.stream().filter(permissions::moduleEnabled).toList();
		return new Summary(modules, kpis, trend,
				top(sales.stream().collect(Collectors.groupingBy(d -> d.getParty().getName(), LinkedHashMap::new,
						Collectors.reducing(BigDecimal.ZERO, TradeDocument::getTaxableTotal, BigDecimal::add))), 5),
				top(balances.stream().filter(b -> b.status() != StockMovement.Status.REJECTED)
						.collect(Collectors.groupingBy(b -> b.category() == null ? "Uncategorised" : b.category(), LinkedHashMap::new,
								Collectors.reducing(BigDecimal.ZERO, StockBalance::value, BigDecimal::add))), 6),
				allWorkOrders.stream().collect(Collectors.groupingBy(w -> w.getStatus().name(), LinkedHashMap::new, Collectors.counting()))
						.entrySet().stream().map(e -> new NameValue(e.getKey(), BigDecimal.valueOf(e.getValue()))).toList(),
				alerts, recent);
	}

	private boolean on(String module) {
		return permissions.moduleEnabled(module);
	}

	private static <T> T when(boolean enabled, T value) {
		return enabled ? value : null;
	}

	private List<TradeDocument> posted(DocKind kind) {
		return documents.findByKindAndStatusIn(kind, List.of(DocStatus.POSTED, DocStatus.PARTLY_PAID, DocStatus.PAID));
	}

	static <T> BigDecimal sum(List<T> rows, Predicate<T> filter, Function<T, BigDecimal> value) {
		return rows.stream().filter(filter).map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	static BigDecimal percent(BigDecimal part, BigDecimal whole) {
		return whole.signum() == 0 ? BigDecimal.ZERO
				: part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
	}

	private static List<NameValue> top(Map<String, BigDecimal> values, int limit) {
		return values.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder()))
				.limit(limit).map(e -> new NameValue(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP))).toList();
	}
}
