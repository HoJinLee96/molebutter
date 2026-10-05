package cc.ataglace.molebutter.attendance.internal;

import org.springframework.data.domain.Page;
import cc.ataglace.molebutter.common.api.PageResponse;
final class JpaPages {private JpaPages(){} static <T> PageResponse<T> from(Page<T> p){return new PageResponse<>(p.getContent(),p.getNumber(),p.getTotalPages(),p.getTotalElements());}}
