package com.endustry.erp.inventory;

import java.util.List;

import org.springframework.stereotype.Component;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.documents.DocStatus;
import com.endustry.erp.documents.DocSupport;
import com.endustry.erp.documents.DocumentLine;
import com.endustry.erp.documents.DocumentPolicy;
import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.documents.TradeDocument;
import com.endustry.erp.masters.Item;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.settings.SettingsService;
import com.endustry.erp.quality.QualityApi.InspectionService;

import lombok.RequiredArgsConstructor;

/**
 * PUR-05 and PUR-06: goods receipt against a purchase order. Posting brings stock in, within the
 * configured over-receipt tolerance, and holds anything needing inspection in QC.
 */
@Component
@RequiredArgsConstructor
class GrnPolicy implements DocumentPolicy {

	private final DocSupport support;
	private final StockService stock;
	private final SettingsService settings;
	private final InspectionService inspections;

	@Override
	public DocKind kind() {
		return DocKind.GRN;
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
		if (doc.getSourceDoc() == null || doc.getLines().stream().anyMatch(l -> l.getSourceLineId() == null)) {
			throw ApiException.rule("GRN_REQUIRES_PO", "Goods can be received only against an approved purchase order line");
		}
		support.requireWarehouse(doc);
		support.drawFromSource(doc, settings.number("purchase.grnTolerancePct"), "OVER_RECEIPT");
		StockService.Ref ref = new StockService.Ref("GRN", doc.getId(), doc.getDocNo());
		for (DocumentLine line : doc.getLines()) {
			Item item = line.getItem();
			if (!item.getType().isStocked()) {
				continue;
			}
			if (item.isBatchTracked() && line.getBatchNo() == null) {
				line.setBatchNo(doc.getDocNo() + "-" + line.getLineNo());
			}
			String batch = item.isBatchTracked() ? line.getBatchNo() : "";
			StockMovement.Status status = item.isQcRequired() ? StockMovement.Status.QC_PENDING : StockMovement.Status.AVAILABLE;
			stock.receive(item, doc.getWarehouse(), batch, status, line.getQty(), line.netRate(), StockMovement.Type.GRN, ref,
					doc.getDocDate());
			if (item.isQcRequired()) {
				inspections.open(item, doc.getWarehouse(), doc.getParty(), batch, line.getQty(), doc.getId(), doc.getDocNo(),
						doc.getDocDate());
			}
		}
		doc.setStatus(DocStatus.POSTED);
	}
}
