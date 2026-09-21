package cc.ataglace.molebutter.domain;

/**
 * 계정 상태.
 * - PENDING: 직원 가입 신청 후 관리자 승인 대기. 로그인/refresh 모두 차단하며 승인 시 ACTIVE로 전이한다.
 * - ACTIVE: 정상.
 * - LOCKED: 로그인 실패 누적으로 일시 잠금
 * - SUSPENDED: 관리자가 수동으로 비활성화한 계정(* 아직 자동 해제 없음).
 */
public enum UserStatus {
    PENDING,
    ACTIVE,
    LOCKED,
    SUSPENDED
}
