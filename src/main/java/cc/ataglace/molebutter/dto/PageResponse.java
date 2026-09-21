package cc.ataglace.molebutter.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** 페이징 목록 응답 공통 형식. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int totalPages,
        long totalElements) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getTotalPages(), page.getTotalElements());
    }
}
