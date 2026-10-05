package cc.ataglace.molebutter.operations.internal;


import org.springframework.data.jpa.repository.JpaRepository;

import cc.ataglace.molebutter.operations.internal.OperationAuditLog;

public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long>{

}
