package cc.ataglace.molebutter.domain;

import lombok.Getter;

@Getter
public enum MenuSection {

    /** 반자동 작업 진행 — 기존 첫 화면(로고 진입점 습관 유지를 위해 최상단). */
    WORKFLOW("작업", pages("/workflow")),
    /** 통합 대시보드 + 확인 목록(worklist). 헤더 worklist 배지 노출도 이 권한을 따른다. */
    DASHBOARD("대시보드", pages("/dashboard")),

    /** 전 계정 공통 본인 출퇴근/휴게 기록. VIEWER의 첫 실사용 landing이 되도록 VERSION_HISTORY 앞에 둔다. */
    ATTENDANCE("근태", pages("/attendance"), apis("/api/attendance/**")),
    /** 대표 전용 전체 근태 조회·정정·집계·CSV. */
    ATTENDANCE_MANAGE("근태 관리", pages("/attendance-manage"), apis("/api/attendance-manage/**")),
    /** 대표 전용 로그인/인증 감사 로그(IP/User-Agent 포함). */
    AUTH_LOGS("인증 로그", pages("/auth-logs"), apis("/api/auth-logs/**")),
    /** 대표 전용 직원 계정 관리(가입 승인·역할 지정·정지·잠금 해제). */
    USER_MANAGE("직원 관리", pages("/user-manage"), apis("/api/admin/users/**")),

    VERSION_HISTORY("버전 기록", pages("/version-history"));

    /** 섹션 권한 authority 접두사. JwtAuthenticationFilter 부여 ↔ SecurityConfig·메뉴 노출 검사에서 공유. */
    public static final String PERMISSION_PREFIX = "PERM_";

    /** 헤더 메뉴에 표시할 이름. */
    private final String label;
    private final String[] pagePatterns;
    private final String[] apiPatterns;

    MenuSection(String label, String[] pagePatterns) {
        this(label, pagePatterns, new String[0]);
    }

    MenuSection(String label, String[] pagePatterns, String[] apiPatterns) {
        this.label = label;
        this.pagePatterns = pagePatterns;
        this.apiPatterns = apiPatterns;
    }

    /** 헤더/홈 메뉴에서 링크로 쓸 대표 페이지 경로(첫 pagePattern). */
    public String homePath() {
        return pagePatterns.length > 0 ? pagePatterns[0] : "/";
    }

    private static String[] pages(String... patterns) {
        return patterns;
    }

    private static String[] apis(String... patterns) {
        return patterns;
    }

}
