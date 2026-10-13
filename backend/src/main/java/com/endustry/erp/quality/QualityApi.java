package com.endustry.erp.quality;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.inventory.StockMovement;
import com.endustry.erp.inventory.StockService;
import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.LookupRepository;
import com.endustry.erp.masters.Party;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.common.BaseRepository;
import com.endustry.erp.platform.common.PageResponse;
import com.endustry.erp.platform.security.CurrentUser;
import com.endustry.erp.platform.settings.SettingsService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

public final class QualityApi {

	private QualityApi() {
	}

	public interface InspectionRepository extends BaseRepository<Inspection> {
		List<Inspection> findByStatus(Inspection.Status status);
	}

	public record InspectionResult(
			@NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") @Digits(integer = 12, fraction = 3) BigDecimal acceptedQty,
			@NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") @Digits(integer = 12, fraction = 3) BigDecimal rejectedQty,
			@Size(max = 30) String reasonCode,
			@Size(max = 400) String remarks) {
	}

	@Service
	@RequiredArgsConstructor
	@Transactional
	public static class InspectionService {

		private final InspectionRepository inspections;
		private final StockService stock;
		private final SettingsService settings;
		private final LookupRepository lookups;
		private final AuditService audit;

		/** Called when goods needing inspection are received; the stock waits in QC until a result is recorded. */
		public Inspection open(Item item, Warehouse warehouse, Party vendor, String batchNo, BigDecimal qty, Long grnId,
				String grnNo, LocalDate date) {
			Inspection inspection = new Inspection();
			inspection.setInspectionNo(settings.nextNumber("INSPECTION", date));
			inspection.setItem(item);
			inspection.setWarehouse(warehouse);
			inspection.setVendor(vendor);
			inspection.setBatchNo(batchNo);
			inspection.setReceivedQty(qty);
			inspection.setGrnId(grnId);
			inspection.setGrnNo(grnNo);
			return inspections.save(inspection);
		}

		@Transactional(readOnly = true)
		public org.springframework.data.domain.Page<Inspection> list(String status, Pageable pageable) {
			if (status == null || status.isBlank()) {
				return inspections.findAll(pageable);
			}
			Inspection.Status parsed;
			try {
				parsed = Inspection.Status.valueOf(status.toUpperCase());
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("INVALID_FILTER", "'" + status + "' is not an inspection status");
			}
			return inspections.findAll((root, query, cb) -> cb.equal(root.get("status"), parsed), pageable);
		}

		/** QLT-04: records the disposition and releases or rejects the stock accordingly. */
		public Inspection complete(Long id, InspectionResult result) {
			Inspection inspection = inspections.findById(id).orElseThrow(() -> ApiException.notFound("Inspection", id));
			if (inspection.getStatus() == Inspection.Status.COMPLETED) {
				throw ApiException.conflict("ALREADY_INSPECTED", inspection.getInspectionNo() + " has already been completed");
			}
			if (result.acceptedQty().add(result.rejectedQty()).compareTo(inspection.getReceivedQty()) != 0) {
				throw ApiException.rule("QTY_MISMATCH", "Accepted plus rejected must equal the received quantity of "
						+ inspection.getReceivedQty().stripTrailingZeros().toPlainString());
			}
			boolean rejecting = result.rejectedQty().signum() > 0;
			if (rejecting && (result.reasonCode() == null || result.reasonCode().isBlank())) {
				throw ApiException.rule("REASON_REQUIRED", "Choose a rejection reason");
			}
			if (rejecting && !lookups.existsByLookupTypeAndCode("REJECTION_REASON", result.reasonCode())) {
				throw ApiException.rule("UNKNOWN_REASON", "'" + result.reasonCode() + "' is not a rejection reason");
			}
			StockService.Ref ref = new StockService.Ref("INSPECTION", inspection.getId(), inspection.getInspectionNo());
			String batch = inspection.getBatchNo() == null ? "" : inspection.getBatchNo();
			stock.changeStatus(inspection.getItem(), inspection.getWarehouse(), batch, StockMovement.Status.QC_PENDING,
					StockMovement.Status.AVAILABLE, result.acceptedQty(), StockMovement.Type.QC_RELEASE, ref, LocalDate.now());
			stock.changeStatus(inspection.getItem(), inspection.getWarehouse(), batch, StockMovement.Status.QC_PENDING,
					StockMovement.Status.REJECTED, result.rejectedQty(), StockMovement.Type.QC_REJECT, ref, LocalDate.now());
			inspection.setAcceptedQty(result.acceptedQty());
			inspection.setRejectedQty(result.rejectedQty());
			inspection.setReasonCode(rejecting ? result.reasonCode() : null);
			inspection.setRemarks(result.remarks());
			inspection.setStatus(Inspection.Status.COMPLETED);
			inspection.setInspectedBy(CurrentUser.email());
			inspection.setInspectedAt(Instant.now());
			audit.note("Inspection", id, "COMPLETE", Map.of("inspectionNo", inspection.getInspectionNo(),
					"accepted", result.acceptedQty(), "rejected", result.rejectedQty()));
			return inspections.save(inspection);
		}
	}

	@RestController
	@RequestMapping("/api/quality/inspections")
	@RequiredArgsConstructor
	static class InspectionController {

		private final InspectionService service;

		@GetMapping
		PageResponse<Inspection> list(@RequestParam(required = false) String status,
				@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
			return PageResponse.of(service.list(status, pageable));
		}

		@PostMapping("/{id}/complete")
		Inspection complete(@PathVariable Long id, @Valid @RequestBody InspectionResult body) {
			return service.complete(id, body);
		}
	}
}
