package com.endustry.erp.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.ItemRepository;
import com.endustry.erp.masters.MasterRepositories.LookupRepository;
import com.endustry.erp.masters.MasterRepositories.WarehouseRepository;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.common.PageResponse;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

	private final StockService stock;
	private final StockMovementRepository movements;
	private final ItemRepository items;
	private final WarehouseRepository warehouses;
	private final LookupRepository lookups;
	private final AuditService audit;

	public record Adjustment(
			@NotNull(message = "is required") Long itemId,
			@NotNull(message = "is required") Long warehouseId,
			@Size(max = 40) String batchNo,
			@NotNull(message = "is required") @Digits(integer = 12, fraction = 3) BigDecimal qtyChange,
			@NotBlank(message = "is required") String reasonCode) {
	}

	public record Transfer(
			@NotNull(message = "is required") Long itemId,
			@NotNull(message = "is required") Long fromWarehouseId,
			@NotNull(message = "is required") Long toWarehouseId,
			@NotNull(message = "is required") @Positive(message = "must be more than zero") @Digits(integer = 12, fraction = 3) BigDecimal qty) {
	}

	@GetMapping("/stock")
	public List<StockBalance> stock(@RequestParam(required = false) String q,
			@RequestParam(required = false) Long warehouseId) {
		String needle = q == null ? "" : q.trim().toLowerCase();
		return stock.balances().stream()
				.filter(b -> warehouseId == null || b.warehouseId().equals(warehouseId))
				.filter(b -> needle.isEmpty() || b.itemCode().toLowerCase().contains(needle)
						|| b.itemName().toLowerCase().contains(needle) || b.batchNo().toLowerCase().contains(needle))
				.toList();
	}

	@GetMapping("/movements")
	public PageResponse<StockMovement> movements(@RequestParam(required = false) Long itemId,
			@RequestParam(required = false) String q,
			@PageableDefault(size = 25, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(movements.findAll((root, query, cb) -> {
			Predicate all = cb.conjunction();
			if (itemId != null) {
				all = cb.and(all, cb.equal(root.get("item").get("id"), itemId));
			}
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase() + "%";
				all = cb.and(all, cb.or(cb.like(cb.lower(root.get("item").get("code")), like),
						cb.like(cb.lower(root.get("item").get("name")), like), cb.like(cb.lower(root.get("refNo")), like)));
			}
			return all;
		}, pageable));
	}

	@PostMapping("/adjustments")
	@Transactional
	public Map<String, Object> adjust(@Valid @RequestBody Adjustment body) {
		if (body.qtyChange().signum() == 0) {
			throw ApiException.badRequest("ZERO_ADJUSTMENT", "The quantity change cannot be zero");
		}
		if (!lookups.existsByLookupTypeAndCode("ADJUSTMENT_REASON", body.reasonCode())) {
			throw ApiException.rule("UNKNOWN_REASON", "'" + body.reasonCode() + "' is not an adjustment reason");
		}
		Item item = item(body.itemId());
		Warehouse warehouse = warehouse(body.warehouseId());
		stock.adjust(item, warehouse, body.batchNo(), body.qtyChange(), body.reasonCode(), LocalDate.now());
		audit.note("Stock", item.getId(), "ADJUST", Map.of("item", item.getCode(), "warehouse", warehouse.getCode(),
				"qtyChange", body.qtyChange(), "reason", body.reasonCode()));
		return Map.of("item", item.getCode(), "available", stock.available(item, warehouse));
	}

	@PostMapping("/transfers")
	@Transactional
	public Map<String, Object> transfer(@Valid @RequestBody Transfer body) {
		Item item = item(body.itemId());
		Warehouse from = warehouse(body.fromWarehouseId());
		Warehouse to = warehouse(body.toWarehouseId());
		stock.transfer(item, from, to, body.qty(), LocalDate.now());
		audit.note("Stock", item.getId(), "TRANSFER",
				Map.of("item", item.getCode(), "from", from.getCode(), "to", to.getCode(), "qty", body.qty()));
		return Map.of("item", item.getCode(), "available", stock.available(item, to));
	}

	private Item item(Long id) {
		return items.findById(id).orElseThrow(() -> ApiException.rule("UNKNOWN_ITEM", "The selected item does not exist"));
	}

	private Warehouse warehouse(Long id) {
		return warehouses.findById(id)
				.orElseThrow(() -> ApiException.rule("UNKNOWN_WAREHOUSE", "The selected warehouse does not exist"));
	}
}
