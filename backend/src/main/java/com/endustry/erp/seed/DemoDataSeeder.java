package com.endustry.erp.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.documents.DocumentRequests.DocumentRequest;
import com.endustry.erp.documents.DocumentRequests.Line;
import com.endustry.erp.documents.DocumentRequests.PaymentRequest;
import com.endustry.erp.documents.DocumentService;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.inventory.StockService;
import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.EmployeeRepository;
import com.endustry.erp.masters.MasterRepositories.ItemRepository;
import com.endustry.erp.masters.MasterRepositories.LookupRepository;
import com.endustry.erp.masters.MasterRepositories.PartyRepository;
import com.endustry.erp.masters.MasterRepositories.TaxRateRepository;
import com.endustry.erp.masters.MasterRepositories.WarehouseRepository;
import com.endustry.erp.masters.MasterRepositories.WorkCentreRepository;
import com.endustry.erp.masters.Party;
import com.endustry.erp.masters.SimpleMasters.Employee;
import com.endustry.erp.masters.SimpleMasters.Lookup;
import com.endustry.erp.masters.SimpleMasters.TaxRate;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.masters.SimpleMasters.WorkCentre;
import com.endustry.erp.platform.validation.GstinValidator;
import com.endustry.erp.production.Bom;
import com.endustry.erp.production.BomService;
import com.endustry.erp.production.ProductionTypes.BomLineRequest;
import com.endustry.erp.production.ProductionTypes.BomOperationRequest;
import com.endustry.erp.production.ProductionTypes.BomRequest;
import com.endustry.erp.production.ProductionTypes.JobCardRequest;
import com.endustry.erp.production.ProductionTypes.OutputRequest;
import com.endustry.erp.production.ProductionTypes.WorkOrderRequest;
import com.endustry.erp.production.WorkOrder;
import com.endustry.erp.production.WorkOrderService;
import com.endustry.erp.quality.Inspection;
import com.endustry.erp.quality.QualityApi.InspectionRepository;
import com.endustry.erp.quality.QualityApi.InspectionResult;
import com.endustry.erp.quality.QualityApi.InspectionService;

import lombok.RequiredArgsConstructor;

/**
 * A sample engineering factory with six months of buying, making and selling, driven through the
 * same services the screens use so that every figure on the dashboard is real system output.
 * Called by {@link StartupSeeder} with the demo client selected; does nothing if that client
 * already has items.
 */
@Component
@RequiredArgsConstructor
public class DemoDataSeeder {

	private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
	private static final int MONTHS = 6;
	/** Share of a normal month's sales in each seeded month; the last is the month in progress. */
	private static final double[] SALES_FACTOR = { 0.70, 0.80, 0.85, 0.95, 1.00, 0.45 };

	private final ItemRepository items;
	private final PartyRepository parties;
	private final WarehouseRepository warehouses;
	private final WorkCentreRepository workCentres;
	private final EmployeeRepository employees;
	private final LookupRepository lookups;
	private final TaxRateRepository taxRates;
	private final DocumentService documents;
	private final BomService boms;
	private final WorkOrderService workOrders;
	private final InspectionService inspectionService;
	private final InspectionRepository inspections;
	private final StockService stock;

	private final Map<String, Item> item = new LinkedHashMap<>();
	private final Map<String, Party> party = new LinkedHashMap<>();
	private Warehouse mainStore;
	private LocalDate today;

	@Transactional
	public void seed() {
		if (items.count() > 0) {
			return;
		}
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("system", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
		try {
			today = LocalDate.now();
			seedMasters();
			seedBoms();
			for (int month = 0; month < MONTHS; month++) {
				boolean current = month == MONTHS - 1;
				buy(month, current);
				make(month, current);
				sell(month, current);
			}
			seedWorkInHand();
			log.info("Demo data loaded: {} items, {} parties", items.count(), parties.count());
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	// ---- masters ----

	private void seedMasters() {
		// Every client starts with a main store; the demo adds a second one and its own product categories.
		mainStore = warehouses.findAll().get(0);
		warehouse("FG", "Finished Goods Store");
		lookups.save(Lookup.of("ITEM_CATEGORY", "SHAFTS", "Shafts", 20));
		lookups.save(Lookup.of("ITEM_CATEGORY", "GEARS", "Gears", 21));
		lookups.save(Lookup.of("ITEM_CATEGORY", "ASSEMBLY", "Assemblies", 22));

		workCentre("LATHE-02", "Turning Centre 2", 350);
		workCentre("CNC-01", "CNC Machining Centre 1", 600);
		workCentre("GRIND-01", "Cylindrical Grinder", 450);
		workCentre("ASSY-01", "Assembly Bench", 200);

		employee("E001", "Santosh More", "Machine Shop", "CNC operator", Employee.WageType.MONTHLY, 24000);
		employee("E002", "Imran Shaikh", "Machine Shop", "Turner", Employee.WageType.HOURLY, 110);
		employee("E003", "Lata Gaikwad", "Assembly", "Fitter", Employee.WageType.PIECE_RATE, 6);
		employee("E004", "Dinesh Kamble", "Machine Shop", "Grinder", Employee.WageType.HOURLY, 120);

		item("RM-EN8-40", "EN8 Round Bar 40 mm", Item.Type.RAW_MATERIAL, "STEEL", "KG", "7214", 18, 78, 0, 500, 7, true, true);
		item("RM-EN24-50", "EN24 Alloy Bar 50 mm", Item.Type.RAW_MATERIAL, "STEEL", "KG", "7228", 18, 145, 0, 300, 10, true, true);
		item("RM-CI-BLANK", "CI Casting Blank (Hub)", Item.Type.RAW_MATERIAL, "CASTING", "NOS", "7325", 18, 220, 0, 100, 14, false, false);
		item("BO-BRG-6205", "Ball Bearing 6205", Item.Type.COMPONENT, "BOUGHT_OUT", "NOS", "8482", 18, 165, 0, 150, 5, false, false);
		item("BO-SEAL-35", "Oil Seal 35 mm", Item.Type.COMPONENT, "BOUGHT_OUT", "NOS", "4016", 18, 38, 0, 200, 5, false, false);
		item("CN-CUT-OIL", "Cutting Oil", Item.Type.CONSUMABLE, "CONSUMABLE", "LTR", "2710", 18, 210, 0, 50, 4, false, false);
		item("PK-BOX-M", "Corrugated Box Medium", Item.Type.CONSUMABLE, "PACKING", "NOS", "4819", 12, 12, 0, 150, 3, false, false);
		item("FG-SHAFT-40", "Drive Shaft 40 mm", Item.Type.FINISHED, "SHAFTS", "NOS", "8483", 18, 0, 1450, 0, 0, true, false);
		item("FG-GEAR-24T", "Spur Gear 24T", Item.Type.FINISHED, "GEARS", "NOS", "8483", 18, 0, 980, 0, 0, false, false);
		item("FG-HUB-ASSY", "Wheel Hub Assembly", Item.Type.FINISHED, "ASSEMBLY", "NOS", "8708", 18, 0, 2350, 0, 0, false, false);
		item("SV-MACHINING", "Machining job charges", Item.Type.SERVICE, "SERVICE", "NOS", "9988", 18, 0, 400, 0, 0, false, false);

		// Illustrates a dated rate change (GST-01); confirm actual rates against current notifications.
		taxRate("4819", "Cartons and boxes of paper", 12, LocalDate.of(2017, 7, 1));
		taxRate("4819", "Cartons and boxes of paper", 5, LocalDate.of(2025, 9, 22));

		customer("C-BHARAT", "Bharat Auto Components Ltd", "27", "AABCB7391K", "Pune", 2500000, 45);
		customer("C-SAHYADRI", "Sahyadri Tractors Pvt Ltd", "27", "AAPCS5520M", "Nashik", 1500000, 30);
		customer("C-KAVERI", "Kaveri Pumps and Motors", "33", "AAFCK2864R", "Coimbatore", 800000, 30);
		customer("C-GUJAGRO", "Gujarat Agro Implements", "24", "AAHCG6107D", "Rajkot", 100000, 30);

		vendor("V-DECCAN", "Deccan Steel Traders", "27", "AACCD9042H", "Pune", 30, null, false);
		vendor("V-MAHALAXMI", "Mahalaxmi Alloys", "27", "AAMFM3318B", "Kolhapur", 60, "UDYAM-MH-18-0093451", true);
		vendor("V-PRECISION", "Precision Bearings Co", "24", "AAJFP7765Q", "Rajkot", 30, "UDYAM-GJ-22-0017204", false);
		vendor("V-OMPACK", "Om Packaging Industries", "27", "AAEFO4429L", "Pune", 30, "UDYAM-MH-26-0056012", true);

		stock.adjust(item.get("CN-CUT-OIL"), mainStore, null, BigDecimal.valueOf(20), "OPENING", date(0, 1));
	}

	private void seedBoms() {
		bom("FG-SHAFT-40",
				List.of(line("RM-EN8-40", 4.2, 3), line("BO-SEAL-35", 1, 0), line("PK-BOX-M", 0.1, 0)),
				List.of(op("Turning", "LATHE-02", 30, 12), op("CNC milling", "CNC-01", 20, 8), op("Grinding", "GRIND-01", 15, 6)));
		bom("FG-GEAR-24T",
				List.of(line("RM-EN24-50", 2.8, 4)),
				List.of(op("Turning", "LATHE-02", 25, 9), op("Gear hobbing", "CNC-01", 40, 14)));
		bom("FG-HUB-ASSY",
				List.of(line("RM-CI-BLANK", 1, 0), line("BO-BRG-6205", 2, 0), line("BO-SEAL-35", 1, 0), line("PK-BOX-M", 1, 0)),
				List.of(op("CNC machining", "CNC-01", 30, 10), op("Assembly", "ASSY-01", 10, 7)));
	}

	// ---- one month of activity ----

	private void buy(int month, boolean current) {
		// The latest month's bills stay unpaid so payables and MSME alerts have something to show.
		boolean pay = month < MONTHS - 2;
		purchase(month, "V-DECCAN", pay, month == 2 ? 40 : 0, Map.of("RM-EN8-40", 1200.0, "RM-CI-BLANK", 100.0));
		purchase(month, "V-MAHALAXMI", pay, 0, Map.of("RM-EN24-50", 500.0));
		if (month % 2 == 0) {
			purchase(month, "V-PRECISION", pay, 0, Map.of("BO-BRG-6205", 400.0, "BO-SEAL-35", 700.0));
		}
		if (month % 3 == 0) {
			purchase(month, "V-OMPACK", pay, 0, Map.of("PK-BOX-M", 400.0));
		}
	}

	private void purchase(int month, String vendorCode, boolean pay, double rejectQty, Map<String, Double> qtyByItem) {
		Party vendor = party.get(vendorCode);
		List<Line> lines = new ArrayList<>();
		qtyByItem.forEach((code, qty) -> lines.add(new Line(item.get(code).getId(), BigDecimal.valueOf(qty),
				item.get(code).getPurchasePrice(), null, null, null, null)));
		TradeDocument po = documents.create(DocKind.PURCHASE_ORDER,
				new DocumentRequest(vendor.getId(), date(month, 2), mainStore.getId(), date(month, 9), null, null, lines));
		documents.act(DocKind.PURCHASE_ORDER, po.getId(), "approve", null);

		TradeDocument grn = redate(documents.derive(DocKind.GRN, po.getId()), date(month, 5), null);
		documents.act(DocKind.GRN, grn.getId(), "post", null);
		for (Inspection inspection : inspections.findByStatus(Inspection.Status.PENDING)) {
			BigDecimal rejected = BigDecimal.valueOf(inspection.getItem().getCode().equals("RM-EN8-40") ? rejectQty : 0);
			inspectionService.complete(inspection.getId(), new InspectionResult(inspection.getReceivedQty().subtract(rejected),
					rejected, rejected.signum() > 0 ? "MATERIAL" : null, null));
		}

		TradeDocument bill = redate(documents.derive(DocKind.PURCHASE_INVOICE, grn.getId()), date(month, 6),
				vendorCode.substring(2, 5) + "/" + (1000 + month * 17 + vendorCode.length()));
		bill = documents.act(DocKind.PURCHASE_INVOICE, bill.getId(), "post", null);
		if (pay) {
			documents.pay(DocKind.PURCHASE_INVOICE, bill.getId(),
					new PaymentRequest(bill.getGrandTotal(), earliest(bill.getDueDate(), today), "NEFT", "UTR" + (48210000 + bill.getId())));
		}
	}

	private void make(int month, boolean current) {
		produce(month, "FG-SHAFT-40", 200, month == 1 ? 8 : 3, "DIMENSION");
		produce(month, "FG-GEAR-24T", 120, month == 3 ? 6 : 2, "SURFACE");
		produce(month, "FG-HUB-ASSY", 60, 0, null);
	}

	private void produce(int month, String itemCode, int qty, int rejected, String reason) {
		WorkOrder wo = workOrders.create(new WorkOrderRequest(item.get(itemCode).getId(), null, BigDecimal.valueOf(qty),
				mainStore.getId(), date(month, 7), date(month, 14), null, null));
		workOrders.release(wo.getId(), false);
		workOrders.bookOutput(wo.getId(), new OutputRequest(BigDecimal.valueOf(qty - rejected), BigDecimal.valueOf(rejected),
				reason, date(month, 12)));
	}

	private void sell(int month, boolean current) {
		double f = SALES_FACTOR[month];
		boolean paid = month < MONTHS - 2;
		sale(month, "C-BHARAT", paid ? 1.0 : month == MONTHS - 2 ? 0.5 : 0, Map.of("FG-SHAFT-40", 150 * f, "FG-GEAR-24T", 60 * f));
		sale(month, "C-SAHYADRI", paid ? 1.0 : 0, Map.of("FG-HUB-ASSY", 50 * f, "FG-GEAR-24T", 40 * f));
		sale(month, "C-KAVERI", paid ? 1.0 : 0, Map.of("FG-SHAFT-40", 40 * f));
	}

	private void sale(int month, String customerCode, double paidShare, Map<String, Double> qtyByItem) {
		Party customer = party.get(customerCode);
		List<Line> lines = new ArrayList<>();
		qtyByItem.forEach((code, qty) -> lines.add(new Line(item.get(code).getId(), BigDecimal.valueOf(Math.round(qty)),
				item.get(code).getSalePrice(), null, null, null, null)));
		TradeDocument order = documents.create(DocKind.SALES_ORDER, new DocumentRequest(customer.getId(), date(month, 13),
				mainStore.getId(), date(month, 20), "PO-" + customerCode.substring(2, 5) + "-" + (4100 + month * 23), null, lines));
		documents.act(DocKind.SALES_ORDER, order.getId(), "confirm", null);

		TradeDocument invoice = redate(documents.derive(DocKind.SALES_INVOICE, order.getId()), date(month, 16), order.getReferenceNo());
		invoice = documents.act(DocKind.SALES_INVOICE, invoice.getId(), "post", null);
		if (paidShare > 0) {
			BigDecimal amount = invoice.getGrandTotal().multiply(BigDecimal.valueOf(paidShare)).setScale(2, RoundingMode.HALF_UP);
			documents.pay(DocKind.SALES_INVOICE, invoice.getId(),
					new PaymentRequest(amount, earliest(invoice.getDocDate().plusDays(21), today), "NEFT", "UTR" + (73350000 + invoice.getId())));
		}
	}

	/** Open items so every list and workflow stage has something in it on first login. */
	private void seedWorkInHand() {
		Long shaft = item.get("FG-SHAFT-40").getId();
		Long gear = item.get("FG-GEAR-24T").getId();
		Long hub = item.get("FG-HUB-ASSY").getId();

		TradeDocument sent = documents.create(DocKind.QUOTATION, new DocumentRequest(party.get("C-KAVERI").getId(), today.minusDays(3),
				null, today.plusDays(27), "RFQ/KPM/118", "Delivery four weeks from order", List.of(
						new Line(shaft, BigDecimal.valueOf(300), BigDecimal.valueOf(1420), BigDecimal.valueOf(2), null, null, null))));
		documents.act(DocKind.QUOTATION, sent.getId(), "send", null);
		documents.create(DocKind.QUOTATION, new DocumentRequest(party.get("C-GUJAGRO").getId(), today, null, null, null, null,
				List.of(new Line(gear, BigDecimal.valueOf(80), BigDecimal.valueOf(980), null, null, null, null),
						new Line(item.get("SV-MACHINING").getId(), BigDecimal.valueOf(20), BigDecimal.valueOf(400), null, null, null, null))));

		TradeDocument open = documents.create(DocKind.SALES_ORDER, new DocumentRequest(party.get("C-SAHYADRI").getId(), today.minusDays(2),
				mainStore.getId(), today.plusDays(12), "PO-SAH-4298", null,
				List.of(new Line(hub, BigDecimal.valueOf(90), BigDecimal.valueOf(2350), null, null, null, null))));
		documents.act(DocKind.SALES_ORDER, open.getId(), "confirm", null);

		TradeDocument onOrder = documents.create(DocKind.PURCHASE_ORDER, new DocumentRequest(party.get("V-PRECISION").getId(),
				today.minusDays(1), mainStore.getId(), today.plusDays(6), null, null,
				List.of(new Line(item.get("BO-BRG-6205").getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(165), null, null, null, null))));
		documents.act(DocKind.PURCHASE_ORDER, onOrder.getId(), "approve", null);
		// Left in draft: above the approval limit, so only the Owner can approve it.
		documents.create(DocKind.PURCHASE_ORDER, new DocumentRequest(party.get("V-DECCAN").getId(), today, mainStore.getId(),
				today.plusDays(10), null, "Quarterly bar stock", List.of(
						new Line(item.get("RM-EN8-40").getId(), BigDecimal.valueOf(6000), BigDecimal.valueOf(78), null, null, null, null),
						new Line(item.get("RM-EN24-50").getId(), BigDecimal.valueOf(800), BigDecimal.valueOf(145), null, null, null, null))));

		// Received but not yet inspected, so quality has a batch waiting.
		TradeDocument po = documents.create(DocKind.PURCHASE_ORDER, new DocumentRequest(party.get("V-MAHALAXMI").getId(),
				today.minusDays(2), mainStore.getId(), today.plusDays(3), null, null,
				List.of(new Line(item.get("RM-EN24-50").getId(), BigDecimal.valueOf(250), BigDecimal.valueOf(146), null, null, null, null))));
		documents.act(DocKind.PURCHASE_ORDER, po.getId(), "approve", null);
		TradeDocument grn = redate(documents.derive(DocKind.GRN, po.getId()), today, null);
		documents.act(DocKind.GRN, grn.getId(), "post", null);

		WorkOrder running = workOrders.create(new WorkOrderRequest(hub, null, BigDecimal.valueOf(90), mainStore.getId(),
				today.minusDays(1), today.plusDays(8), open.getDocNo(), "For " + open.getDocNo()));
		running = workOrders.release(running.getId(), true);
		workOrders.jobCard(running.getJobCards().get(0).getId(), "start", new JobCardRequest(employees.findAll().get(0).getId(), null, null, null));
		workOrders.bookOutput(running.getId(), new OutputRequest(BigDecimal.valueOf(30), BigDecimal.ZERO, null, today));
		workOrders.create(new WorkOrderRequest(shaft, null, BigDecimal.valueOf(250), mainStore.getId(), today, today.plusDays(10), null, null));
		workOrders.create(new WorkOrderRequest(gear, null, BigDecimal.valueOf(150), mainStore.getId(), today.minusDays(12), today.minusDays(2), null,
				"Held: waiting for hobbing cutter"));
	}

	// ---- helpers ----

	/** Re-saves a freshly derived draft with a past date and, optionally, a reference number. */
	private TradeDocument redate(TradeDocument doc, LocalDate date, String referenceNo) {
		List<Line> lines = doc.getLines().stream().map(l -> new Line(l.getItem().getId(), l.getQty(), l.getRate(), l.getDiscountPct(),
				l.getBatchNo(), l.getDescription(), l.getSourceLineId())).toList();
		return documents.update(doc.getKind(), doc.getId(), new DocumentRequest(doc.getParty().getId(), date, mainStore.getId(), null,
				referenceNo, null, lines));
	}

	/** Day {@code day} of the month that is {@code MONTHS - 1 - month} months back, never after today. */
	private LocalDate date(int month, int day) {
		return earliest(today.minusMonths(MONTHS - 1L - month).withDayOfMonth(day), today);
	}

	private static LocalDate earliest(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}

	private Warehouse warehouse(String code, String name) {
		Warehouse w = new Warehouse();
		w.setCode(code);
		w.setName(name);
		return warehouses.save(w);
	}

	private void workCentre(String code, String name, int hourlyCost) {
		WorkCentre w = new WorkCentre();
		w.setCode(code);
		w.setName(name);
		w.setLocation("Shop floor");
		w.setHourlyCost(BigDecimal.valueOf(hourlyCost));
		w.setShifts(2);
		workCentres.save(w);
	}

	private void employee(String code, String name, String department, String skill, Employee.WageType wageType, int rate) {
		Employee e = new Employee();
		e.setCode(code);
		e.setName(name);
		e.setDepartment(department);
		e.setSkill(skill);
		e.setWageType(wageType);
		e.setWageRate(BigDecimal.valueOf(rate));
		employees.save(e);
	}

	private void item(String code, String name, Item.Type type, String category, String uom, String hsn, int gst, int purchasePrice,
			int salePrice, int reorderLevel, int leadTime, boolean batchTracked, boolean qcRequired) {
		Item i = new Item();
		i.setCode(code);
		i.setName(name);
		i.setType(type);
		i.setCategory(category);
		i.setUom(uom);
		i.setHsnCode(hsn);
		i.setGstRate(BigDecimal.valueOf(gst));
		i.setPurchasePrice(BigDecimal.valueOf(purchasePrice));
		i.setSalePrice(BigDecimal.valueOf(salePrice));
		i.setReorderLevel(BigDecimal.valueOf(reorderLevel));
		i.setLeadTimeDays(leadTime);
		i.setBatchTracked(batchTracked);
		i.setQcRequired(qcRequired);
		item.put(code, items.save(i));
	}

	private void taxRate(String hsn, String description, int rate, LocalDate from) {
		TaxRate t = new TaxRate();
		t.setHsnCode(hsn);
		t.setDescription(description);
		t.setRatePct(BigDecimal.valueOf(rate));
		t.setEffectiveFrom(from);
		taxRates.save(t);
	}

	private void customer(String code, String name, String state, String pan, String city, int creditLimit, int creditDays) {
		Party p = newParty(code, name, Party.Type.CUSTOMER, state, pan, city, creditDays);
		p.setCreditLimit(BigDecimal.valueOf(creditLimit));
		party.put(code, parties.save(p));
	}

	private void vendor(String code, String name, String state, String pan, String city, int creditDays, String udyam, boolean agreedTerms) {
		Party p = newParty(code, name, Party.Type.VENDOR, state, pan, city, creditDays);
		p.setMsmeRegistered(udyam != null);
		p.setUdyamNumber(udyam);
		p.setMsmeAgreedTerms(agreedTerms);
		party.put(code, parties.save(p));
	}

	private static Party newParty(String code, String name, Party.Type type, String state, String pan, String city, int creditDays) {
		Party p = new Party();
		p.setCode(code);
		p.setName(name);
		p.setType(type);
		p.setStateCode(state);
		p.setPan(pan);
		p.setGstin(GstinValidator.withCheckDigit(state + pan + "1Z"));
		p.setCity(city);
		p.setCreditDays(creditDays);
		p.setContactPerson("Accounts");
		return p;
	}

	private void bom(String itemCode, List<BomLineRequest> lines, List<BomOperationRequest> operations) {
		Bom bom = boms.create(new BomRequest(item.get(itemCode).getId(), BigDecimal.ONE, null, lines, operations));
		boms.approve(bom.getId());
	}

	private BomLineRequest line(String componentCode, double qty, double scrapPct) {
		return new BomLineRequest(item.get(componentCode).getId(), BigDecimal.valueOf(qty), BigDecimal.valueOf(scrapPct));
	}

	private BomOperationRequest op(String name, String workCentreCode, int setupMinutes, int runMinutes) {
		Long id = workCentres.findAll(Pageable.unpaged()).stream().filter(w -> w.getCode().equals(workCentreCode)).findFirst().orElseThrow().getId();
		return new BomOperationRequest(name, id, BigDecimal.valueOf(setupMinutes), BigDecimal.valueOf(runMinutes));
	}
}
