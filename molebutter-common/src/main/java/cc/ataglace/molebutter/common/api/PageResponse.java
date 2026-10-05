package cc.ataglace.molebutter.common.api;


import java.util.List;



/** 페이징 목록 응답 공통 형식. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int totalPages,
        long totalElements) {

}
