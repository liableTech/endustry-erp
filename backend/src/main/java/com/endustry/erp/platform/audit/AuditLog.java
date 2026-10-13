package com.endustry.erp.platform.audit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.TenantId;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Append-only record of who changed what. There is deliberately no update or delete path. */
@Entity
@Table(name = "audit_log", indexes = @Index(columnList = "entityType,entityId"))
@Getter
@Setter
public class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@JsonIgnore
	@Column(name = "tenant_id", nullable = false, updatable = false, length = 40)
	private String tenantId;

	private String entityType;
	private Long entityId;
	private String action;
	private String actor;
	private Instant at;

	/** JSON object of {field: {from, to}} for updates, or the full record for create / delete. */
	@Column(length = 8000)
	private String changes;
}
