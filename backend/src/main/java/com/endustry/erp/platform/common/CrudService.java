package com.endustry.erp.platform.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.audit.AuditService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/**
 * Reusable create / read / update / delete for flat master records. A new master needs only an
 * entity, a repository and a three-line subclass; search, filtering, paging, optimistic locking
 * and the audit trail come from here.
 */
@Transactional
public abstract class CrudService<T extends BaseEntity> {

	private static final Set<String> RESERVED_PARAMS = Set.of("q", "page", "size", "sort");
	private static final Set<String> RANGE_OPERATORS = Set.of("from", "to", "like");
	private static final String[] SYSTEM_FIELDS = { "id", "version", "tenantId", "createdAt", "updatedAt", "createdBy", "updatedBy" };

	protected final BaseRepository<T> repo;
	private final String entityName;
	private final List<String> searchFields;

	@Autowired
	protected AuditService audit;

	@PersistenceContext
	protected EntityManager em;

	protected CrudService(BaseRepository<T> repo, String entityName, List<String> searchFields) {
		this.repo = repo;
		this.entityName = entityName;
		this.searchFields = searchFields;
	}

	/** Cross-field and business checks. {@code existing} is null on create. */
	protected void validate(T incoming, T existing) {
	}

	/** Fields the system maintains and a client must never overwrite. */
	protected String[] protectedFields() {
		return new String[0];
	}

	@Transactional(readOnly = true)
	public Page<T> list(String q, Map<String, String> params, Pageable pageable) {
		return repo.findAll(spec(q, params), pageable);
	}

	@Transactional(readOnly = true)
	public T get(Long id) {
		return repo.findById(id).orElseThrow(() -> ApiException.notFound(entityName, id));
	}

	public T create(T incoming) {
		incoming.setId(null);
		incoming.setVersion(null);
		validate(incoming, null);
		T saved = repo.saveAndFlush(incoming);
		em.refresh(saved);
		audit.record(entityName, saved.getId(), "CREATE", null, saved);
		return saved;
	}

	public T update(Long id, T incoming) {
		T existing = get(id);
		if (incoming.getVersion() != null && !incoming.getVersion().equals(existing.getVersion())) {
			throw ApiException.conflict("STALE_RECORD", "Someone else changed this record. Reload and try again.");
		}
		validate(incoming, existing);
		Map<String, Object> before = audit.snapshot(existing);
		List<String> ignored = new ArrayList<>(Arrays.asList(SYSTEM_FIELDS));
		ignored.addAll(Arrays.asList(protectedFields()));
		BeanUtils.copyProperties(incoming, existing, ignored.toArray(String[]::new));
		T saved = repo.saveAndFlush(existing);
		em.refresh(saved);
		audit.record(entityName, id, "UPDATE", before, saved);
		return saved;
	}

	public void delete(Long id) {
		T existing = get(id);
		Map<String, Object> before = audit.snapshot(existing);
		repo.delete(existing);
		repo.flush();
		audit.record(entityName, id, "DELETE", before, null);
	}

	/**
	 * Free-text search across {@link #searchFields} plus filters on any attribute: {@code type=RAW,FG}
	 * matches any of the values, {@code name.like=bolt} matches text containing it, and
	 * {@code price.from=10&price.to=50} matches a range of numbers or dates.
	 */
	private Specification<T> spec(String q, Map<String, String> params) {
		return (root, query, cb) -> {
			List<Predicate> all = new ArrayList<>();
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase() + "%";
				all.add(cb.or(searchFields.stream()
						.map(f -> cb.like(cb.lower(root.get(f).as(String.class)), like))
						.toArray(Predicate[]::new)));
			}
			if (params != null) {
				params.forEach((key, value) -> {
					if (RESERVED_PARAMS.contains(key) || value == null || value.isBlank()) {
						return;
					}
					int dot = key.lastIndexOf('.');
					String operator = dot > 0 && RANGE_OPERATORS.contains(key.substring(dot + 1)) ? key.substring(dot + 1) : "in";
					Path<Object> path;
					try {
						path = root.get("in".equals(operator) ? key : key.substring(0, dot));
					} catch (IllegalArgumentException unknownAttribute) {
						return;
					}
					all.add(Filters.predicate(cb, path, operator, value));
				});
			}
			return cb.and(all.toArray(Predicate[]::new));
		};
	}

}
