package cc.ataglace.molebutter.repository;

import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import cc.ataglace.molebutter.domain.attendance.*;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    Optional<Attendance> findByUserIdAndWorkDate(Long userId, LocalDate date);
    Optional<Attendance> findByIdAndUserId(Long id, Long userId);
    Optional<Attendance> findFirstByUserIdAndStatusInOrderByWorkDateDesc(Long userId, Collection<AttendanceStatus> statuses);
    Page<Attendance> findByUserIdAndWorkDateBetween(Long userId, LocalDate from, LocalDate to, Pageable page);
    Optional<Attendance> findFirstByUserIdAndWorkDateLessThanOrderByWorkDateDesc(Long userId, LocalDate date);
    Optional<Attendance> findFirstByUserIdAndWorkDateGreaterThanOrderByWorkDateAsc(Long userId, LocalDate date);
}
