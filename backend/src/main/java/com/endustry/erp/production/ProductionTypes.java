package com.endustry.erp.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.endustry.erp.platform.common.BaseRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Repositories, request bodies and read models of the production module. */
public final class ProductionTypes {

	private ProductionTypes() {
	}

	// ---- repositories ----

	public interface BomRepository extends BaseRepository<Bom> {

		Optional<Bom> findFirstByItemIdAndStatus(Long itemId, Bom.Status status);

		List<Bom> findByItemId(Long itemId);

		@Query("select distinct b from Bom b join b.lines l where l.component.id = :componentId")
		List<Bom> findUsing(Long componentId);
	}

	public interface WorkOrderRepository extends BaseRepository<WorkOrder> {

		List<WorkOrder> findByStatusIn(Collection<WorkOrder.Status> statuses);

		@Query("select w from WorkOrder w join w.jobCards j where j.id = :jobCardId")
		Optional<WorkOrder> findByJobCard(Long jobCardId);
	}

	public interface OutputRepository extends JpaRepository<WorkOrder.Output, Long> {
		List<WorkOrder.Output> findByWorkOrderIdOrderByIdAsc(Long workOrderId);
	}

	// ---- requests ----

	public record BomRequest(
			@NotNull(message = "is required") Long itemId,
			@NotNull(message = "is required") @Positive(message = "must be more than zero") @Digits(integer = 12, fraction = 3) BigDecimal outputQty,
			@Size(max = 400) String notes,
			@NotEmpty(message = "needs at least one component") @Size(max = 200) @Valid List<BomLineRequest> lines,
			@Size(max = 50) @Valid List<BomOperationRequest> operations) {
	}

	public record BomLineRequest(
			@NotNull(message = "is required") Long componentId,
			@NotNull(message = "is required") @Positive(message = "must be more than zero") @Digits(integer = 12, fraction = 4) BigDecimal qty,
			@DecimalMin(value = "0", message = "cannot be negative") @DecimalMax(value = "50", message = "cannot be more than 50") BigDecimal scrapPct) {
	}

	public record BomOperationRequest(
			@NotBlank(message = "is required") @Size(max = 80) String name,
			@NotNull(message = "is required") Long workCentreId,
			@PositiveOrZero(message = "cannot be negative") BigDecimal setupMinutes,
			@NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") BigDecimal runMinutesPerUnit) {
	}

	public record WorkOrderRequest(
			@NotNull(message = "is required") Long itemId,
			Long bomId,
			@NotNull(message = "is required") @Positive(message = "must be more than zero") @Digits(integer = 12, fraction = 3) BigDecimal plannedQty,
			@NotNull(message = "is required") Long warehouseId,
			LocalDate startDate,
			@NotNull(message = "is required") LocalDate dueDate,
			@Size(max = 16) String salesOrderNo,
			@Size(max = 400) String notes) {
	}

	public record OutputRequest(
			@NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") @Digits(integer = 12, fraction = 3) BigDecimal goodQty,
			@NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") @Digits(integer = 12, fraction = 3) BigDecimal rejectedQty,
			@Size(max = 30) String reasonCode,
			LocalDate outputDate) {
	}

	public record JobCardRequest(Long operatorId, @Size(max = 30) String reasonCode,
			@PositiveOrZero(message = "cannot be negative") BigDecimal goodQty,
			@PositiveOrZero(message = "cannot be negative") BigDecimal rejectedQty) {
	}

	// ---- read models ----

	public record BomCost(BigDecimal materialCost, BigDecimal operationCost, BigDecimal totalCost, BigDecimal unitCost) {
	}

	/** One component of a work order: what it needs against what is on the shelf (PPC-06). */
	public record MaterialNeed(Long itemId, String itemCode, String itemName, String uom, BigDecimal required,
			BigDecimal available, BigDecimal shortage) {
	}

	/** One line of the material plan across all open work orders and reorder levels (PPC-04). */
	public record MaterialPlanLine(Long itemId, String itemCode, String itemName, String uom, BigDecimal required,
			BigDecimal available, BigDecimal onOrder, BigDecimal reorderLevel, BigDecimal suggestedQty, int leadTimeDays) {
	}
}
