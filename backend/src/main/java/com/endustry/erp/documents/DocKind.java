package com.endustry.erp.documents;

import java.util.Arrays;
import java.util.Set;

import com.endustry.erp.masters.Party;
import com.endustry.erp.platform.common.ApiException;

/**
 * Every commercial document is the same shape: a party, dated lines of items, and tax. What
 * differs per kind is who the party is, which document it is raised from, and its rules, which
 * live in a {@link DocumentPolicy}.
 */
public enum DocKind {

	QUOTATION("sales", "quotation", Party.Type.CUSTOMER, null, Set.of()),
	SALES_ORDER("sales", "sales-order", Party.Type.CUSTOMER, QUOTATION, Set.of(DocStatus.SENT, DocStatus.ACCEPTED)),
	SALES_INVOICE("sales", "sales-invoice", Party.Type.CUSTOMER, SALES_ORDER,
			Set.of(DocStatus.CONFIRMED, DocStatus.PARTLY_FULFILLED)),
	PURCHASE_ORDER("purchase", "purchase-order", Party.Type.VENDOR, null, Set.of()),
	GRN("inventory", "grn", Party.Type.VENDOR, PURCHASE_ORDER, Set.of(DocStatus.APPROVED, DocStatus.PARTLY_FULFILLED)),
	PURCHASE_INVOICE("purchase", "purchase-invoice", Party.Type.VENDOR, GRN,
			Set.of(DocStatus.POSTED, DocStatus.PARTLY_FULFILLED));

	private final String module;
	private final String slug;
	private final Party.Type partyRole;
	private final DocKind source;
	private final Set<DocStatus> sourceStatuses;

	DocKind(String module, String slug, Party.Type partyRole, DocKind source, Set<DocStatus> sourceStatuses) {
		this.module = module;
		this.slug = slug;
		this.partyRole = partyRole;
		this.source = source;
		this.sourceStatuses = sourceStatuses;
	}

	/** The permission module this kind belongs to; also the first segment of its URL. */
	public String module() {
		return module;
	}

	public String slug() {
		return slug;
	}

	public Party.Type partyRole() {
		return partyRole;
	}

	/** The kind this one is normally raised from, or null. */
	public DocKind source() {
		return source;
	}

	/** Whether a document of this kind can be raised from {@code doc} right now. */
	public boolean canDeriveFrom(TradeDocument doc) {
		return source == doc.getKind() && sourceStatuses.contains(doc.getStatus());
	}

	public boolean isInvoice() {
		return this == SALES_INVOICE || this == PURCHASE_INVOICE;
	}

	public static DocKind fromSlug(String module, String slug) {
		return Arrays.stream(values()).filter(k -> k.slug.equals(slug) && k.module.equals(module)).findFirst()
				.orElseThrow(() -> ApiException.notFound("Document type", slug));
	}
}
