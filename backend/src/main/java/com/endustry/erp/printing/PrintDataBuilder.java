package com.endustry.erp.printing;

import static com.endustry.erp.printing.PrintFormat.date;
import static com.endustry.erp.printing.PrintFormat.humanize;
import static com.endustry.erp.printing.PrintFormat.money;
import static com.endustry.erp.printing.PrintFormat.nz;
import static com.endustry.erp.printing.PrintFormat.pct;
import static com.endustry.erp.printing.PrintFormat.qty;
import static com.endustry.erp.printing.PrintFormat.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocumentLine;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.masters.Party;
import com.endustry.erp.platform.settings.CompanyProfile;
import com.endustry.erp.platform.settings.SettingsService;
import com.endustry.erp.production.Bom;
import com.endustry.erp.production.ProductionTypes.BomCost;
import com.endustry.erp.production.ProductionTypes.MaterialNeed;
import com.endustry.erp.production.WorkOrder;
import com.endustry.erp.production.WorkOrder.JobCard;

import lombok.RequiredArgsConstructor;

/**
 * Turns a saved document into the plain JSON a print template is filled from. Amounts and dates
 * are already formatted as text so a template needs no logic beyond {{placeholders}} and
 * {{#lists}}...{{/lists}}. docs/print-templates/README.md describes every field.
 */
@Component
@RequiredArgsConstructor
public class PrintDataBuilder {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

	private final SettingsService settings;

	public Map<String, Object> trade(TradeDocument doc) {
		PrintDocType type = PrintDocType.of(doc.getKind());
		CompanyProfile company = settings.company();
		boolean interState = doc.getIgst().signum() > 0
				|| (doc.getPlaceOfSupply() != null && !doc.getPlaceOfSupply().equals(company.getStateCode()));
		boolean customer = doc.getKind().partyRole() == Party.Type.CUSTOMER;

		Map<String, Object> data = base(type, company);
		data.put("document", map(
				"number", doc.getDocNo(),
				"date", date(doc.getDocDate()),
				"dueDate", date(doc.getDueDate()),
				"dueLabel", dueLabel(doc.getKind()),
				"referenceNo", text(doc.getReferenceNo()),
				"referenceLabel", referenceLabel(doc.getKind()),
				"status", doc.getStatus().name(),
				"statusLabel", humanize(doc.getStatus().name()),
				"isDraft", doc.getStatus() == DocStatus.DRAFT,
				"isCancelled", doc.getStatus() == DocStatus.CANCELLED,
				"placeOfSupply", text(doc.getPlaceOfSupply()),
				"sourceNumber", doc.getSourceDoc() == null ? "" : doc.getSourceDoc().getDocNo(),
				"warehouse", doc.getWarehouse() == null ? "" : doc.getWarehouse().getName(),
				"notes", text(doc.getNotes()),
				"ewayBillRequired", doc.isEwayBillRequired()));

		Party p = doc.getParty();
		data.put("party", map(
				"label", customer ? "Customer" : "Vendor",
				"billToLabel", customer ? "Bill to" : "Vendor",
				"code", text(p.getCode()),
				"name", text(p.getName()),
				"gstin", text(p.getGstin()),
				"pan", text(p.getPan()),
				"address", text(p.getAddress()),
				"city", text(p.getCity()),
				"pincode", text(p.getPincode()),
				"addressLine", addressLine(p.getAddress(), p.getCity(), p.getPincode()),
				"stateCode", text(p.getStateCode()),
				"contactPerson", text(p.getContactPerson()),
				"phone", text(p.getPhone()),
				"email", text(p.getEmail())));

		List<Map<String, Object>> lines = new ArrayList<>();
		Map<String, BigDecimal[]> byHsn = new LinkedHashMap<>();
		for (DocumentLine l : doc.getLines()) {
			BigDecimal tax = nz(l.getTaxAmount());
			BigDecimal half = tax.divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
			String hsn = text(l.getItem().getHsnCode());
			lines.add(map(
					"lineNo", l.getLineNo(),
					"itemCode", l.getItem().getCode(),
					"itemName", l.getItem().getName(),
					"description", text(l.getDescription()),
					"hsnCode", hsn,
					"batchNo", text(l.getBatchNo()),
					"qty", qty(l.getQty()),
					"uom", text(l.getUom()),
					"rate", money(l.getRate()),
					"discountPct", pct(l.getDiscountPct()),
					"hasDiscount", nz(l.getDiscountPct()).signum() > 0,
					"taxRatePct", pct(l.getTaxRatePct()),
					"taxableAmount", money(l.getTaxableAmount()),
					"cgstAmount", interState ? money(BigDecimal.ZERO) : money(half),
					"sgstAmount", interState ? money(BigDecimal.ZERO) : money(tax.subtract(half)),
					"igstAmount", interState ? money(tax) : money(BigDecimal.ZERO),
					"taxAmount", money(tax),
					"lineTotal", money(l.getLineTotal())));
			BigDecimal[] sums = byHsn.computeIfAbsent(hsn + "|" + pct(l.getTaxRatePct()),
					k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
			sums[0] = sums[0].add(nz(l.getTaxableAmount()));
			sums[1] = sums[1].add(tax);
		}
		data.put("lines", lines);

		List<Map<String, Object>> summary = new ArrayList<>();
		byHsn.forEach((key, sums) -> {
			String[] parts = key.split("\\|", -1);
			BigDecimal half = sums[1].divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
			summary.add(map(
					"hsnCode", parts[0],
					"taxRatePct", parts[1],
					"taxableAmount", money(sums[0]),
					"cgstAmount", interState ? money(BigDecimal.ZERO) : money(half),
					"sgstAmount", interState ? money(BigDecimal.ZERO) : money(sums[1].subtract(half)),
					"igstAmount", interState ? money(sums[1]) : money(BigDecimal.ZERO),
					"taxAmount", money(sums[1])));
		});
		data.put("taxSummary", summary);

		data.put("totals", map(
				"subTotal", money(doc.getSubTotal()),
				"discountTotal", money(doc.getDiscountTotal()),
				"hasDiscount", nz(doc.getDiscountTotal()).signum() > 0,
				"taxableTotal", money(doc.getTaxableTotal()),
				"interState", interState,
				"cgst", money(doc.getCgst()),
				"sgst", money(doc.getSgst()),
				"igst", money(doc.getIgst()),
				"taxTotal", money(doc.getTaxTotal()),
				"grandTotal", money(doc.getGrandTotal()),
				"paidAmount", money(doc.getPaidAmount()),
				"balance", money(doc.getBalance()),
				"hasPayments", nz(doc.getPaidAmount()).signum() > 0,
				"amountInWords", PrintFormat.rupeesInWords(doc.getGrandTotal())));
		return data;
	}

	public Map<String, Object> workOrder(WorkOrder wo, List<MaterialNeed> materials) {
		Map<String, Object> data = base(PrintDocType.WORK_ORDER, settings.company());
		data.put("document", map(
				"number", wo.getWoNo(),
				"status", wo.getStatus().name(),
				"statusLabel", humanize(wo.getStatus().name()),
				"isDraft", wo.getStatus() == WorkOrder.Status.PLANNED,
				"isCancelled", wo.getStatus() == WorkOrder.Status.CANCELLED,
				"startDate", date(wo.getStartDate()),
				"dueDate", date(wo.getDueDate()),
				"completedDate", date(wo.getCompletedDate()),
				"salesOrderNo", text(wo.getSalesOrderNo()),
				"warehouse", wo.getWarehouse().getName(),
				"bomVersion", wo.getBom().getVersionNo(),
				"notes", text(wo.getNotes())));
		data.put("item", map(
				"code", wo.getItem().getCode(),
				"name", wo.getItem().getName(),
				"uom", text(wo.getItem().getUom())));
		data.put("quantities", map(
				"planned", qty(wo.getPlannedQty()),
				"produced", qty(wo.getProducedQty()),
				"rejected", qty(wo.getRejectedQty()),
				"remaining", qty(wo.getRemainingQty())));
		data.put("costs", map(
				"standardUnitCost", money(wo.getStandardUnitCost()),
				"materialCost", money(wo.getMaterialCost()),
				"operationCost", money(wo.getOperationCost()),
				"actualUnitCost", money(wo.getActualUnitCost())));
		data.put("materials", materials.stream().map(m -> map(
				"itemCode", m.itemCode(),
				"itemName", m.itemName(),
				"uom", text(m.uom()),
				"required", qty(m.required()),
				"available", qty(m.available()),
				"shortage", qty(m.shortage()),
				"isShort", m.shortage().signum() > 0)).toList());
		data.put("jobCards", wo.getJobCards().stream().map(this::jobCard).toList());
		return data;
	}

	/** A bill of material: what one run makes, what goes into it, the routing and the standard cost. */
	public Map<String, Object> bom(Bom bom, BomCost cost) {
		Map<String, Object> data = base(PrintDocType.BOM, settings.company());
		String number = bom.getItem().getCode() + "-v" + bom.getVersionNo();
		data.put("document", map(
				"number", number,
				"version", bom.getVersionNo(),
				"status", bom.getStatus().name(),
				"statusLabel", humanize(bom.getStatus().name()),
				"isDraft", bom.getStatus() == Bom.Status.DRAFT,
				"outputQty", qty(bom.getOutputQty()),
				"notes", text(bom.getNotes())));
		data.put("item", map(
				"code", bom.getItem().getCode(),
				"name", bom.getItem().getName(),
				"uom", text(bom.getItem().getUom())));
		data.put("costs", map(
				"materialCost", money(cost.materialCost()),
				"operationCost", money(cost.operationCost()),
				"totalCost", money(cost.totalCost()),
				"unitCost", money(cost.unitCost())));
		data.put("components", bom.getLines().stream().map(l -> map(
				"lineNo", l.getLineNo(),
				"itemCode", l.getComponent().getCode(),
				"itemName", l.getComponent().getName(),
				"uom", text(l.getComponent().getUom()),
				"qty", qty(l.getQty()),
				"scrapPct", pct(l.getScrapPct()),
				"hasScrap", l.getScrapPct().signum() > 0)).toList());
		data.put("operations", bom.getOperations().stream().map(o -> map(
				"seqNo", o.getSeqNo(),
				"name", o.getName(),
				"workCentre", o.getWorkCentre().getName(),
				"setupMinutes", qty(o.getSetupMinutes()),
				"runMinutesPerUnit", qty(o.getRunMinutesPerUnit()))).toList());
		return data;
	}

	private Map<String, Object> jobCard(JobCard c) {
		return map(
				"seqNo", c.getSeqNo(),
				"operationName", c.getOperationName(),
				"workCentre", c.getWorkCentre().getName(),
				"operator", c.getOperator() == null ? "" : c.getOperator().getName(),
				"status", c.getStatus().name(),
				"statusLabel", humanize(c.getStatus().name()),
				"minutesWorked", c.getMinutesWorked(),
				"goodQty", qty(c.getGoodQty()),
				"rejectedQty", qty(c.getRejectedQty()),
				"pauseReason", text(c.getPauseReason()));
	}

	private Map<String, Object> base(PrintDocType type, CompanyProfile c) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("documentType", type.name());
		data.put("documentTitle", type.title());
		String display = c.getTradeName() == null || c.getTradeName().isBlank() ? c.getLegalName() : c.getTradeName();
		data.put("company", map(
				"legalName", text(c.getLegalName()),
				"tradeName", text(c.getTradeName()),
				"displayName", text(display),
				"gstin", text(c.getGstin()),
				"pan", text(c.getPan()),
				"udyamNumber", text(c.getUdyamNumber()),
				"address", text(c.getAddress()),
				"city", text(c.getCity()),
				"pincode", text(c.getPincode()),
				"addressLine", addressLine(c.getAddress(), c.getCity(), c.getPincode()),
				"stateCode", text(c.getStateCode()),
				"phone", text(c.getPhone()),
				"email", text(c.getEmail())));
		data.put("printedAt", LocalDateTime.now().format(STAMP));
		return data;
	}

	/** "Plot 14, MIDC Bhosari, Pune - 411026", leaving out whatever is not filled in. */
	private static String addressLine(String address, String city, String pincode) {
		String line = Stream.of(address, city).filter(v -> v != null && !v.isBlank()).collect(Collectors.joining(", "));
		return pincode == null || pincode.isBlank() ? line : (line.isEmpty() ? pincode : line + " - " + pincode);
	}

	private static String referenceLabel(DocKind kind) {
		return switch (kind) {
			case QUOTATION -> "Enquiry reference";
			case SALES_ORDER, SALES_INVOICE -> "Customer PO no";
			case PURCHASE_ORDER -> "Vendor quotation ref";
			case GRN -> "Vendor challan no";
			case PURCHASE_INVOICE -> "Vendor invoice no";
		};
	}

	private static String dueLabel(DocKind kind) {
		return switch (kind) {
			case QUOTATION -> "Valid until";
			case SALES_ORDER -> "Delivery date";
			case PURCHASE_ORDER -> "Expected by";
			case PURCHASE_INVOICE -> "Pay by";
			default -> "Due date";
		};
	}

	/** An insertion-ordered map from alternating keys and values, so the JSON reads in a sensible order. */
	static Map<String, Object> map(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}
}
