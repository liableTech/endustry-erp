package com.endustry.erp.documents;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.documents.DocumentRequests.ActionRequest;
import com.endustry.erp.documents.DocumentRequests.DocumentRequest;
import com.endustry.erp.documents.DocumentRequests.PaymentRequest;
import com.endustry.erp.platform.common.PageResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * One controller for all document kinds, e.g. /api/sales/docs/sales-order or
 * /api/inventory/docs/grn. The module segment is what the permission check runs against.
 */
@RestController
@RequestMapping("/api/{module}/docs/{kind}")
@RequiredArgsConstructor
public class DocumentController {

	private final DocumentService service;

	@GetMapping
	public PageResponse<TradeDocument> list(@PathVariable String module, @PathVariable String kind,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) String partyId, @RequestParam Map<String, String> params,
			@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(service.list(DocKind.fromSlug(module, kind), q, status, partyId, params, pageable));
	}

	@GetMapping("/{id}")
	public TradeDocument get(@PathVariable String module, @PathVariable String kind, @PathVariable Long id) {
		return service.get(DocKind.fromSlug(module, kind), id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TradeDocument create(@PathVariable String module, @PathVariable String kind,
			@Valid @RequestBody DocumentRequest body) {
		return service.create(DocKind.fromSlug(module, kind), body);
	}

	@PutMapping("/{id}")
	public TradeDocument update(@PathVariable String module, @PathVariable String kind, @PathVariable Long id,
			@Valid @RequestBody DocumentRequest body) {
		return service.update(DocKind.fromSlug(module, kind), id, body);
	}

	/** Raises a draft of this kind from its source document, e.g. an invoice from a sales order. */
	@PostMapping("/from/{sourceId}")
	@ResponseStatus(HttpStatus.CREATED)
	public TradeDocument derive(@PathVariable String module, @PathVariable String kind, @PathVariable Long sourceId) {
		return service.derive(DocKind.fromSlug(module, kind), sourceId);
	}

	@PostMapping("/{id}/actions/{action}")
	public TradeDocument act(@PathVariable String module, @PathVariable String kind, @PathVariable Long id,
			@PathVariable String action, @Valid @RequestBody(required = false) ActionRequest body) {
		return service.act(DocKind.fromSlug(module, kind), id, action, body);
	}

	@GetMapping("/{id}/payments")
	public List<Payment> payments(@PathVariable String module, @PathVariable String kind, @PathVariable Long id) {
		return service.payments(DocKind.fromSlug(module, kind), id);
	}

	@PostMapping("/{id}/payments")
	public TradeDocument pay(@PathVariable String module, @PathVariable String kind, @PathVariable Long id,
			@Valid @RequestBody PaymentRequest body) {
		return service.pay(DocKind.fromSlug(module, kind), id, body);
	}
}
