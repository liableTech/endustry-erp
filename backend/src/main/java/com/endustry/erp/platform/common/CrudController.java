package com.endustry.erp.platform.common;

import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import jakarta.validation.Valid;

/** Standard REST surface for any {@link CrudService}. */
public abstract class CrudController<T extends BaseEntity> {

	protected final CrudService<T> service;

	protected CrudController(CrudService<T> service) {
		this.service = service;
	}

	@GetMapping
	public PageResponse<T> list(@RequestParam(required = false) String q, @RequestParam Map<String, String> params,
			@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(service.list(q, params, pageable));
	}

	@GetMapping("/{id}")
	public T get(@PathVariable Long id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public T create(@Valid @RequestBody T body) {
		return service.create(body);
	}

	@PutMapping("/{id}")
	public T update(@PathVariable Long id, @Valid @RequestBody T body) {
		return service.update(id, body);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}
}
