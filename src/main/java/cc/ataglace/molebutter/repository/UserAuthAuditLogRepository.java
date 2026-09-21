package cc.ataglace.molebutter.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.domain.audit.UserAuthAuditLog;

public interface UserAuthAuditLogRepository extends JpaRepository<UserAuthAuditLog, Long> {

    /** 인증 로그 검색: 기간 필수(반개구간 [from, to)), 이벤트·이메일·IP(부분 일치)는 선택. */
    @Query("""
            select l from UserAuthAuditLog l
            where l.createdAt >= :from and l.createdAt < :to
              and (:eventType is null or l.eventType = :eventType)
              and (:email is null or l.email like concat('%', :email, '%'))
              and (:ip is null or l.ip like concat('%', :ip, '%'))
            """)
    Page<UserAuthAuditLog> search(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("eventType") UserAuthEventType eventType,
            @Param("email") String email,
            @Param("ip") String ip,
            Pageable pageable);
}
