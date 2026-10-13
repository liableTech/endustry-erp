package com.endustry.erp.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocSupport;
import com.endustry.erp.documents.DocumentLine;
import com.endustry.erp.documents.DocumentPolicy;
import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.inventory.StockMovement;
import com.endustry.erp.inventory.StockService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/** SAL-02: quotation with a validity date. */
@Component
@RequiredArgsConstructor
class QuotationPolicy implements DocumentPolicy {

	private final SettingsService settings;

	@Override
	public DocKind kind() {
		return DocKind.QUOTATION;
	}

	@Override
	public List<String> actions(TradeDocument doc) {
		return switch (doc.getStatus()) {
			case DRAFT -> List.of("send", "cancel");
			case SENT -> List.of("accept", "reject");
			default -> List.of();
		};
	}

	@Override
	public void execute(String action, TradeDocument doc, ActionRequest request) {
		switch (action) {
			case "send" -> {
				if (doc.getDueDate() == null) {
					doc.setDueDate(doc.getDocDate().plusDays(settings.number("sales.quotationValidityDays").longValue()));
				}
				doc.setStatus(DocStatus.SENT);
			}
			case "accept" -> {
				if (doc.getDueDate() != null && doc.getDueDate().isBefore(LocalDate.now())) {
					throw ApiException.rule("QUOTATION_EXPIRED",
							doc.getDocNo() + " expired on " + doc.getDueDate() + ". Raise a revised quotation.");
				}
				doc.setStatus(DocStatus.ACCEPTED);
			}
			case "reject" -> doc.setStatus(DocStatus.REJECTED);
			default -> doc.setStatus(DocStatus.CANCELLED);
		}
	}
}

/** SAL-04 and SAL-07: sales order with a credit check on confirmation. */
@Component
@RequiredArgsConstructor
class SalesOrderPolicy implements DocumentPolicy {

	private final DocSupport support;

	@Override
	public DocKind kind() {
		return DocKind.SALES_ORDER;
	}

	@Override
	public List<String> actions(TradeDocument doc) {
		return switch (doc.getStatus()) {
			case DRAFT -> List.of("confirm", "cancel");
			case CONFIRMED -> List.of("cancel");
			default -> List.of();
		};
	}

	@Override
	public void execute(String action, TradeDocument doc, ActionRequest request) {
		if (action.equals("cancel")) {
			doc.setStatus(DocStatus.CANCELLED);
			return;
		}
		support.checkCredit(doc, request);
		support.drawFromSource(doc, BigDecimal.ZERO, "OVER_ORDERING");
		doc.setStatus(DocStatus.CONFIRMED);
	}
}

/** SAL-10: tax invoice. Posting issues stock, fixes the due date and flags the e-way bill. */
@Component
@RequiredArgsConstructor
class SalesInvoicePolicy implements DocumentPolicy {

	private final DocSupport support;
	private final StockService stock;
	private final SettingsService settings;

	@Override
	public DocKind kind() {
		return DocKind.SALES_INVOICE;
	}

	@Override
	public List<String> actions(TradeDocument doc) {
		return doc.getStatus() == DocStatus.DRAFT ? List.of("post", "cancel") : List.of();
	}

	@Override
	public void execute(String action, TradeDocument doc, ActionRequest request) {
		if (action.equals("cancel")) {
			doc.setStatus(DocStatus.CANCELLED);
			return;
		}
		boolean movesStock = doc.getLines().stream().anyMatch(l -> l.getItem().getType().isStocked());
		if (movesStock) {
			support.requireWarehouse(doc);
		}
		// An invoice raised from a confirmed order was already credit-checked there.
		if (doc.getSourceDoc() == null) {
			support.checkCredit(doc, request);
		}
		support.drawFromSource(doc, BigDecimal.ZERO, "OVER_INVOICING");
		StockService.Ref ref = new StockService.Ref("SALES_INVOICE", doc.getId(), doc.getDocNo());
		for (DocumentLine line : doc.getLines()) {
			if (line.getItem().getType().isStocked()) {
				stock.issue(line.getItem(), doc.getWarehouse(), line.getQty(), StockMovement.Type.SALE, ref, doc.getDocDate());
			}
		}
		if (doc.getDueDate() == null) {
			doc.setDueDate(doc.getDocDate().plusDays(doc.getParty().getCreditDays()));
		}
		doc.setEwayBillRequired(movesStock && doc.getGrandTotal().compareTo(settings.number("gst.ewayBillThreshold")) > 0);
		doc.setStatus(DocStatus.POSTED);
	}
}
