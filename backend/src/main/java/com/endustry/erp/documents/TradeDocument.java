package com.endustry.erp.documents;

import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.endustry.erp.masters.Party;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/** A quotation, order, goods receipt or invoice. See {@link DocKind}. */
@Entity
@Table(name = "trade_document", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "docNo" }), indexes = { @Index(columnList = "kind,status"), @Index(columnList = "party_id") })
@Getter
@Setter
public class TradeDocument extends BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private DocKind kind;

	@Column(nullable = false, updatable = false, length = 16)
	private String docNo;

	@Column(nullable = false)
	private LocalDate docDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DocStatus status = DocStatus.DRAFT;

	@ManyToOne(optional = false)
	private Party party;

	@ManyToOne
	private Warehouse warehouse;

	/** The document this one was raised from, e.g. the sales order behind an invoice. */
	@ManyToOne
	@JsonIgnoreProperties({ "lines", "sourceDoc", "party", "warehouse" })
	private TradeDocument sourceDoc;

	/** Customer PO number on sales documents; the vendor's invoice number on a purchase invoice. */
	private String referenceNo;

	/** Payment due date on invoices; valid-until on quotations; expected date on orders. */
	private LocalDate dueDate;

	/** GST state code that decides CGST + SGST versus IGST. */
	private String placeOfSupply;

	@Column(precision = 19, scale = 2)
	private BigDecimal subTotal = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal discountTotal = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal taxableTotal = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal cgst = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal sgst = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal igst = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal grandTotal = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal paidAmount = BigDecimal.ZERO;

	private boolean ewayBillRequired;

	@Column(length = 500)
	private String notes;

	/** System-raised remark the user should see: credit override, rate variance and the like. */
	@Column(length = 500)
	private String alertNote;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "document_id", nullable = false)
	@OrderBy("lineNo")
	private List<DocumentLine> lines = new ArrayList<>();

	/** What the current user can do next; filled in by the service, never stored. */
	@Transient
	private List<String> allowedActions = List.of();

	/** Slugs of the document kinds that can be raised from this one right now. */
	@Transient
	private List<String> derivations = List.of();

	public BigDecimal getBalance() {
		return grandTotal.subtract(paidAmount);
	}

	public BigDecimal getTaxTotal() {
		return cgst.add(sgst).add(igst);
	}

	public void addAlert(String message) {
		String joined = alertNote == null || alertNote.isBlank() ? message : alertNote + " | " + message;
		alertNote = joined.length() > 500 ? joined.substring(0, 500) : joined;
	}
}
