package cc.ataglace.molebutter.attendance.api;
import cc.ataglace.molebutter.attendance.api.AttendanceDtos.*;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;

import cc.ataglace.molebutter.attendance.api.CorrectionStatus;
import cc.ataglace.molebutter.common.api.PageResponse;



public interface AttendanceService {
    CurrentView current(Long userId);
    PageResponse<RecordView> list(Long userId, String month, int page);
    RecordView detail(Long userId, Long id);
    RecordView clockIn(Long userId, ClockInRequest request);
    RecordView action(Long userId, String action, ActionRequest request);
    PageResponse<CorrectionView> myCorrections(Long userId, String month, int page);
    CorrectionView myCorrection(Long userId, Long id);
    CorrectionView requestCorrection(Long userId, CorrectionRequest request);
    CorrectionView cancel(Long userId, Long requestId, ReviewRequest request);
    PageResponse<CorrectionView> reviewList(Long actor, CorrectionStatus status, int page);
    CorrectionView reviewDetail(Long actor, Long requestId);
    CorrectionView review(Long actor, Long requestId, boolean approve, ReviewRequest request);
}
