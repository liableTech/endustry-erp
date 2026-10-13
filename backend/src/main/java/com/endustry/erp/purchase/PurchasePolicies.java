package com.endustry.erp.purchase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocSupport;
import com.endustry.erp.documents.DocumentLine;
import com.endustry.erp.documents.DocumentPolicy;
import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.masters.Party;
import com.endustry.erp.platform.common.Amounts;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.CurrentUser;
import com.endustry.erp.platform.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/** PUR-03: purchase order with approval by value limit. */
@Component
@RequiredArgsConstructor
class PurchaseOrderPolicy implements DocumentPolicy {

	private final SettingsService settings;

	@Override
	public DocKind kind() {
		return DocKind.PURCHASE_ORDER;
	}

	@Override
	public List<String> actions(TradeDocument doc) {
		return switch (doc.getStatus()) {
			case DRAFT -> List.of("approve", "cancel");
			case APPROVED -> List.of("cancel");
			default -> List.of();
		};
	}

	@Override
	public void execute(String action, TradeDocument doc, ActionRequest request) {
		if (action.equals("cancel")) {
			doc.setStatus(DocStatus.CANCELLED);
			return;
		}
		BigDecimal limit = settings.number("purchase.approvalLimit");
		if (doc.getGrandTotal().compareTo(limit) > 0 && !CurrentUser.isOwner()) {
			throw ApiException.forbidden("APPROVAL_LIMIT_EXCEEDED", doc.getDocNo() + " is above the approval limit of "
					+ Amounts.inr(limit) + " and needs the Owner's approval");
		}
		doc.setStatus(DocStatus.APPROVED);
	}
}

/**
 * PUR-07 and PUR-11: purchase invoice with a three-way match against the receipt and order, and
 * an MSMED Act due date for registered micro and small vendors.
 */
@Component
@RequiredArgsConstructor
class PurchaseInvoicePolicy implements DocumentPolicy {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final DocSupport support;
	private final SettingsService settings;
	private final TradeDocumentRepository documents;

	@Override
	public DocKind kind() {
		return DocKind.PURCHASE_INVOICE;
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
		if (doc.getReferenceNo() == null) {
			throw ApiException.rule("VENDOR_INVOICE_NO_REQUIRED", "Enter the vendor's invoice number before posting");
		}
		if (documents.referenceUsed(DocKind.PURCHASE_INVOICE, doc.getParty().getId(), doc.getReferenceNo(), doc.getId())) {
			throw ApiException.conflict("DUPLICATE_VENDOR_INVOICE",
					"Invoice " + doc.getReferenceNo() + " from " + doc.getParty().getName() + " is already booked");
		}
		flagRateVariance(doc);
		support.drawFromSource(doc, BigDecimal.ZERO, "OVER_BILLING");
		doc.setDueDate(doc.getDocDate().plusDays(paymentDays(doc.getParty())));
		doc.setStatus(DocStatus.POSTED);
	}

	/** Compares the billed rate with the ordered rate carried on the receipt. */
	private void flagRateVariance(TradeDocument doc) {
		if (doc.getSourceDoc() == null) {
			return;
		}
		BigDecimal tolerance = settings.number("purchase.rateVarianceTolerancePct");
		Map<Long, DocumentLine> received = doc.getSourceDoc().getLines().stream()
				.collect(Collectors.toMap(DocumentLine::getId, Function.identity()));
		for (DocumentLine line : doc.getLines()) {
			DocumentLine from = line.getSourceLineId() == null ? null : received.get(line.getSourceLineId());
			if (from == null || from.getRate().signum() == 0) {
				continue;
			}
			BigDecimal variancePct = line.getRate().subtract(from.getRate()).multiply(HUNDRED)
					.divide(from.getRate(), 2, RoundingMode.HALF_UP);
			if (variancePct.abs().compareTo(tolerance) > 0) {
				doc.addAlert("Rate variance on " + line.getItem().getCode() + ": ordered "
						+ from.getRate().stripTrailingZeros().toPlainString() + ", billed "
						+ line.getRate().stripTrailingZeros().toPlainString() + " (" + variancePct.toPlainString() + "%)");
			}
		}
	}

	/**
	 * MSMED Act section 15: a registered micro or small supplier must be paid within the agreed
	 * period capped at 45 days, or within 15 days when nothing is agreed in writing.
	 */
	private long paymentDays(Party vendor) {
		if (!vendor.isMsmeRegistered()) {
			return vendor.getCreditDays();
		}
		return vendor.isMsmeAgreedTerms()
				? Math.min(vendor.getCreditDays(), settings.number("msme.maxCreditDays").longValue())
				: settings.number("msme.defaultCreditDays").longValue();
	}
}
