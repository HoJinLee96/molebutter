package cc.ataglace.molebutter.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 인증 감사 이벤트. 성공/실패는 이벤트를 나누지 않고 success 플래그 + failureReason으로 기록한다
 * (예: 로그인 실패 = SIGNIN + success=false).
 */
@Getter
@AllArgsConstructor
public enum UserAuthEventType {
    SIGNIN("로그인"),
    SIGNOUT("로그아웃"),
    TOKEN_REFRESH("토큰 갱신"),
    PASSWORD_CHANGED("비밀번호 변경"),
    PASSWORD_RESET("비밀번호 재설정"),
    SIGNUP("가입 신청"),
    ACCOUNT_APPROVED("가입 승인"),
    ACCOUNT_ROLE_CHANGED("역할 변경"),
    ACCOUNT_SUSPENDED("계정 정지"),
    ACCOUNT_UNSUSPENDED("정지 해제"),
    ACCOUNT_UNLOCKED("잠금 해제"),
    EMAIL_CODE_SENT("인증번호 발송"),
    RATE_LIMIT_EXCEEDED("요청 제한 초과"),
    ;

    /** 인증 로그 화면에 표시할 이름. */
    private final String label;
}
