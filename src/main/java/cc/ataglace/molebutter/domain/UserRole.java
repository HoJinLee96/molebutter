package cc.ataglace.molebutter.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;

@Getter
public enum UserRole {

    ADMIN("관리자", EnumSet.allOf(MenuSection.class)),

    PRODUCT("상품 담당", EnumSet.of(
            MenuSection.ATTENDANCE,
            MenuSection.VERSION_HISTORY)),

    VIEWER("조회 전용", EnumSet.of(MenuSection.ATTENDANCE, MenuSection.VERSION_HISTORY))
    ;

    /** 화면에 표시할 역할 이름. */
    private final String label;
    private final Set<MenuSection> sections;

    UserRole(String label, Set<MenuSection> sections) {
        this.label = label;
        this.sections = Collections.unmodifiableSet(EnumSet.copyOf(sections));
    }
}
