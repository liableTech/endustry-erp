package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.endustry.erp.documents.DocumentRepositories.TradeDocumentRepository;
import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.masters.Party;
import com.endustry.erp.platform.common.Amounts;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.CurrentUser;
import com.endustry.erp.platform.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/** Rules shared by more than one {@link DocumentPolicy}. */
@Component
@RequiredArgsConstructor
public class DocSupport {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final TradeDocumentRepository documents;
	private final SettingsService settings;

	/**
	 * Draws each line's quantity down from the source document, refusing to take more than is
	 * pending (plus a tolerance), then moves the source to partly or fully fulfilled.
	 */
	public void drawFromSource(TradeDocument doc, BigDecimal tolerancePct, String overCode) {
		TradeDocument source = doc.getSourceDoc();
		if (source == null) {
			return;
		}
		Map<Long, DocumentLine> byId = source.getLines().stream().collect(Collectors.toMap(DocumentLine::getId, Function.identity()));
		for (DocumentLine line : doc.getLines()) {
			if (line.getSourceLineId() == null) {
				continue;
			}
			DocumentLine from = byId.get(line.getSourceLineId());
			BigDecimal ceiling = from.getPendingQty()
					.add(from.getQty().multiply(tolerancePct).divide(HUNDRED));
			if (line.getQty().compareTo(ceiling) > 0) {
				throw ApiException.rule(overCode,
						line.getItem().getCode() + ": " + plain(line.getQty()) + " is more than the " + plain(from.getPendingQty())
								+ " pending on " + source.getDocNo(),
						Map.of("item", line.getItem().getCode(), "pending", from.getPendingQty(), "requested", line.getQty()));
			}
			from.setFulfilledQty(from.getFulfilledQty().add(line.getQty()));
		}
		boolean complete = source.getLines().stream().allMatch(l -> l.getPendingQty().signum() == 0);
		source.setStatus(complete ? DocStatus.FULFILLED : DocStatus.PARTLY_FULFILLED);
	}

	/**
	 * SAL-07: stops a sale that would take the customer past their credit limit. How strict this is
	 * comes from the sales.creditCheckMode parameter; an Owner can override a block.
	 */
	public void checkCredit(TradeDocument doc, ActionRequest request) {
		String mode = settings.text("sales.creditCheckMode");
		Party party = doc.getParty();
		if (mode.equals("OFF") || party.getCreditLimit().signum() == 0) {
			return;
		}
		BigDecimal outstanding = documents.outstanding(party.getId(), DocKind.SALES_INVOICE);
		BigDecimal exposure = outstanding.add(doc.getGrandTotal());
		if (exposure.compareTo(party.getCreditLimit()) <= 0) {
			return;
		}
		String message = party.getName() + " would owe " + Amounts.inr(exposure) + " against a credit limit of "
				+ Amounts.inr(party.getCreditLimit());
		if (mode.equals("WARN")) {
			doc.addAlert("Credit limit exceeded: " + message);
		} else if (request.overrideRequested() && CurrentUser.isOwner()) {
			doc.addAlert("Credit limit overridden by " + CurrentUser.email());
		} else {
			throw ApiException.rule("CREDIT_LIMIT_EXCEEDED", message + ". An Owner can override this.",
					Map.of("creditLimit", party.getCreditLimit(), "outstanding", outstanding, "thisDocument", doc.getGrandTotal()));
		}
	}

	public void requireWarehouse(TradeDocument doc) {
		if (doc.getWarehouse() == null) {
			throw ApiException.rule("WAREHOUSE_REQUIRED", "Choose a warehouse before posting " + doc.getDocNo());
		}
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}
