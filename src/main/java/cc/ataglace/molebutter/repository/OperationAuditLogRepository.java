package cc.ataglace.molebutter.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cc.ataglace.molebutter.domain.audit.OperationAuditLog;

public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long>{

}
