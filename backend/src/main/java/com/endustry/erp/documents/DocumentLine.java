package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.endustry.erp.masters.Item;
import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "trade_document_line")
@Getter
@Setter
public class DocumentLine extends BaseEntity {

	private int lineNo;

	@ManyToOne(optional = false)
	private Item item;

	private String description;

	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal qty;

	private String uom;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal rate;

	@Column(precision = 5, scale = 2)
	private BigDecimal discountPct = BigDecimal.ZERO;

	@Column(precision = 5, scale = 2)
	private BigDecimal taxRatePct = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal taxableAmount = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal taxAmount = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal lineTotal = BigDecimal.ZERO;

	/** Quantity already taken into the next document: invoiced, received or billed. */
	@Column(precision = 19, scale = 3)
	private BigDecimal fulfilledQty = BigDecimal.ZERO;

	private String batchNo;

	/** The line of the source document this one draws from. */
	private Long sourceLineId;

	public BigDecimal getPendingQty() {
		return qty.subtract(fulfilledQty).max(BigDecimal.ZERO);
	}

	/** Rate after discount, before tax: what stock is valued at on receipt. */
	public BigDecimal netRate() {
		return qty.signum() == 0 ? BigDecimal.ZERO : taxableAmount.divide(qty, 4, RoundingMode.HALF_UP);
	}
}
