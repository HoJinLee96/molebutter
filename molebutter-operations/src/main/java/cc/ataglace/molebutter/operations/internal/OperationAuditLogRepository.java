package cc.ataglace.molebutter.operations.internal;


import org.springframework.data.jpa.repository.JpaRepository;


public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long>{

}
