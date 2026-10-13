package com.endustry.erp.production;

import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.SimpleMasters.Employee;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.masters.SimpleMasters.WorkCentre;
import com.endustry.erp.platform.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An order to make a quantity of an item from an approved BOM (PPC-01). */
@Entity
@Table(name = "work_order", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "woNo" }))
@Getter
@Setter
public class WorkOrder extends BaseEntity {

	public enum Status {
		PLANNED, RELEASED, IN_PROGRESS, COMPLETED, CANCELLED;

		public boolean isOpen() {
			return this == PLANNED || this == RELEASED || this == IN_PROGRESS;
		}
	}

	@Column(nullable = false)
	private String woNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.PLANNED;

	@ManyToOne(optional = false)
	private Item item;

	@ManyToOne(optional = false)
	@JsonIgnoreProperties({ "lines", "operations", "item" })
	private Bom bom;

	@ManyToOne(optional = false)
	private Warehouse warehouse;

	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal plannedQty;

	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal producedQty = BigDecimal.ZERO;

	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal rejectedQty = BigDecimal.ZERO;

	@Column(nullable = false)
	private LocalDate startDate;

	@Column(nullable = false)
	private LocalDate dueDate;

	private LocalDate completedDate;

	/** The sales order this is being made for, when make-to-order (PPC-02). */
	private String salesOrderNo;

	/** Cost per unit expected from the BOM when the order was raised (CST-01). */
	@Column(precision = 19, scale = 4)
	private BigDecimal standardUnitCost = BigDecimal.ZERO;

	/** Actual cost of material issued so far (CST-02). */
	@Column(precision = 19, scale = 2)
	private BigDecimal materialCost = BigDecimal.ZERO;

	@Column(precision = 19, scale = 2)
	private BigDecimal operationCost = BigDecimal.ZERO;

	@Column(length = 400)
	private String notes;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "work_order_id", nullable = false)
	@OrderBy("seqNo")
	private List<JobCard> jobCards = new ArrayList<>();

	public BigDecimal getRemainingQty() {
		return plannedQty.subtract(producedQty).subtract(rejectedQty).max(BigDecimal.ZERO);
	}

	public BigDecimal getActualUnitCost() {
		return producedQty.signum() == 0 ? BigDecimal.ZERO
				: materialCost.add(operationCost).divide(producedQty, 4, RoundingMode.HALF_UP);
	}

	/** One routing step of a work order, run from the shop floor (SHP-01, SHP-02). */
	@Entity
	@Table(name = "job_card")
	@Getter
	@Setter
	public static class JobCard extends BaseEntity {

		public enum Status {
			PENDING, RUNNING, PAUSED, DONE
		}

		private int seqNo;

		@Column(nullable = false)
		private String operationName;

		@ManyToOne(optional = false)
		private WorkCentre workCentre;

		@ManyToOne
		private Employee operator;

		@Enumerated(EnumType.STRING)
		@Column(nullable = false)
		private Status status = Status.PENDING;

		/** When the current run began; null while not running. */
		private Instant runningSince;

		private long minutesWorked;

		@Column(precision = 19, scale = 3)
		private BigDecimal goodQty = BigDecimal.ZERO;

		@Column(precision = 19, scale = 3)
		private BigDecimal rejectedQty = BigDecimal.ZERO;

		private String pauseReason;
	}

	/** One booking of finished output against a work order (SHP-03, SHP-06). */
	@Entity(name = "ProductionOutput")
	@Table(name = "production_output")
	@Getter
	@Setter
	public static class Output extends BaseEntity {

		private Long workOrderId;
		private String woNo;

		@ManyToOne(optional = false)
		private Item item;

		@Column(nullable = false)
		private LocalDate outputDate;

		@Column(precision = 19, scale = 3)
		private BigDecimal goodQty;

		@Column(precision = 19, scale = 3)
		private BigDecimal rejectedQty;

		private String reasonCode;

		@Column(precision = 19, scale = 2)
		private BigDecimal materialCost;

		@Column(precision = 19, scale = 2)
		private BigDecimal operationCost;
	}
}
