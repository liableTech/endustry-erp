package com.endustry.erp.platform.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.security.CurrentUser;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AuditService {

	private static final Set<String> NOISE = Set.of("version", "createdAt", "updatedAt", "createdBy", "updatedBy");
	private static final int MAX_CHANGES_LENGTH = 7900;

	private final AuditLogRepository repo;
	private final ObjectMapper mapper;

	@SuppressWarnings("unchecked")
	public Map<String, Object> snapshot(Object entity) {
		return entity == null ? null : mapper.convertValue(entity, Map.class);
	}

	/** Records an action. For updates only the fields that actually changed are stored. */
	@Transactional
	public void record(String entityType, Long entityId, String action, Map<String, Object> before, Object after) {
		Map<String, Object> afterMap = snapshot(after);
		Object payload;
		if (before != null && afterMap != null) {
			Map<String, Object> diff = new LinkedHashMap<>();
			afterMap.forEach((k, v) -> {
				if (!NOISE.contains(k) && !Objects.equals(String.valueOf(before.get(k)), String.valueOf(v))) {
					Map<String, Object> change = new LinkedHashMap<>();
					change.put("from", before.get(k));
					change.put("to", v);
					diff.put(k, change);
				}
			});
			if (diff.isEmpty()) {
				return;
			}
			payload = diff;
		} else {
			payload = afterMap != null ? afterMap : before;
		}
		note(entityType, entityId, action, payload);
	}

	/** Records a business event such as "POST" or "APPROVE" with free-form details. */
	@Transactional
	public void note(String entityType, Long entityId, String action, Object details) {
		AuditLog log = new AuditLog();
		log.setEntityType(entityType);
		log.setEntityId(entityId);
		log.setAction(action);
		log.setActor(CurrentUser.actor());
		log.setAt(Instant.now());
		String json = details == null ? null : mapper.writeValueAsString(details);
		log.setChanges(json != null && json.length() > MAX_CHANGES_LENGTH ? json.substring(0, MAX_CHANGES_LENGTH) : json);
		repo.save(log);
	}

	@Transactional(readOnly = true)
	public Page<AuditLog> list(String entityType, Pageable pageable) {
		return entityType == null || entityType.isBlank() ? repo.findAll(pageable)
				: repo.findByEntityType(entityType, pageable);
	}
}
