package cc.ataglace.molebutter.attendance.api;
import cc.ataglace.molebutter.attendance.api.AttendanceQueryDtos.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.attendance.api.AttendanceStatus;
import cc.ataglace.molebutter.common.api.PageResponse;



public interface AttendanceQueryService {
    PageResponse<RecordRow> records(Long actor, String from, String to, String q, Long userId, AttendanceStatus status, int page);
    RecordRow detail(Long actor, long id);
    PageResponse<SummaryRow> summary(Long actor, String month, String q, Long userId, int page);
    Path recordsCsv(Long actor, String from, String to, String q, Long userId, AttendanceStatus status) throws IOException;
    Path summaryCsv(Long actor, String month, String q, Long userId) throws IOException;
}
