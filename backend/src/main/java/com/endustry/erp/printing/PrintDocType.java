package com.endustry.erp.printing;

import java.util.Arrays;

import com.endustry.erp.documents.DocKind;
import com.endustry.erp.platform.common.ApiException;

/** Everything that can be printed, each with its own choice of template. */
public enum PrintDocType {

	QUOTATION("Quotation", "sales"),
	SALES_ORDER("Sales Order", "sales"),
	SALES_INVOICE("Tax Invoice", "sales"),
	PURCHASE_ORDER("Purchase Order", "purchase"),
	GRN("Goods Receipt Note", "inventory"),
	PURCHASE_INVOICE("Purchase Invoice", "purchase"),
	WORK_ORDER("Work Order and Job Cards", "production"),
	BOM("Bill of Material", "production");

	private final String title;
	private final String module;

	PrintDocType(String title, String module) {
		this.title = title;
		this.module = module;
	}

	/** The heading printed on the document. */
	public String title() {
		return title;
	}

	/** The permission module whose documents these are. */
	public String module() {
		return module;
	}

	public boolean isTrade() {
		return this != WORK_ORDER && this != BOM;
	}

	public static PrintDocType of(DocKind kind) {
		return valueOf(kind.name());
	}

	public static PrintDocType parse(String value) {
		return Arrays.stream(values()).filter(t -> t.name().equalsIgnoreCase(value)).findFirst()
				.orElseThrow(() -> ApiException.badRequest("UNKNOWN_DOC_TYPE", "Unknown document type '" + value + "'"));
	}
}
