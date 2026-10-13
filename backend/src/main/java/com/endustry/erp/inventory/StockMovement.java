package com.endustry.erp.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One line of the stock ledger. Stock on hand is always the sum of movements, so the ledger and
 * the balance can never disagree (INV-01, INV-02).
 */
@Entity
@Table(name = "stock_movement", indexes = @Index(columnList = "item_id,warehouse_id,status"))
@Getter
@Setter
public class StockMovement extends BaseEntity {

	public enum Status {
		AVAILABLE, QC_PENDING, REJECTED
	}

	public enum Type {
		OPENING, GRN, SALE, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN, QC_RELEASE, QC_REJECT, PRODUCTION_ISSUE,
		PRODUCTION_RECEIPT
	}

	@ManyToOne(optional = false)
	private Item item;

	@ManyToOne(optional = false)
	private Warehouse warehouse;

	/** Empty string when the item is not batch tracked. */
	@Column(nullable = false)
	private String batchNo = "";

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	/** Positive for stock in, negative for stock out. */
	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal qty;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal rate;

	@Column(nullable = false)
	private LocalDate movementDate;

	private String refType;
	private Long refId;
	private String refNo;
	private String remarks;
}
