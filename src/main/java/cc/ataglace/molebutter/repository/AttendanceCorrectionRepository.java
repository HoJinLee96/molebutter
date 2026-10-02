package cc.ataglace.molebutter.repository;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import cc.ataglace.molebutter.domain.attendance.*;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, Long> {
    boolean existsByUserIdAndWorkDateAndStatus(Long userId, LocalDate date, CorrectionStatus status);
    Optional<AttendanceCorrection> findByIdAndUserId(Long id, Long userId);
    @Query("select c.userId from AttendanceCorrection c where c.id = :id")
    Optional<Long> findOwner(Long id);
    Page<AttendanceCorrection> findByUserIdAndWorkDateBetween(Long userId, LocalDate from, LocalDate to, Pageable page);
    @Query("select c from AttendanceCorrection c where (:status is null or c.status = :status)")
    Page<AttendanceCorrection> search(CorrectionStatus status, Pageable page);
}
