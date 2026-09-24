package cc.ataglace.molebutter.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(name = "email", length = 255, unique = true, nullable = false)
    private String email;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 30)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false, length = 30)
    private UserStatus status;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** 마지막 비밀번호 변경 시각(감사/표시용). 토큰 무효화는 authVersion으로 판정한다. */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Builder
    private User(String email, String phoneNumber, String name, String passwordHash, UserRole role, UserStatus status) {
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.failedLoginAttempts = 0;
    }

    public boolean isSuspended() {
        return this.status == UserStatus.SUSPENDED;
    }

    public boolean isPending() {
        return this.status == UserStatus.PENDING;
    }

    public boolean isLocked() {
        return this.status == UserStatus.LOCKED;
    }

    /** 로그인/refresh를 허용할 수 없는 상태(정지·잠금·승인대기)인지. */
    public boolean isSigninBlocked() {
        return isSuspended() || isLocked() || isPending();
    }

    /** 로그인 성공: 실패 카운터 초기화 */
    public void signinSuccess(LocalDateTime now) {
        this.failedLoginAttempts = 0;
        this.lastLoginAt = now;
    }

    /**
     * 로그인 실패: 실패 카운터를 증가시키고 임계값을 Locked으로 설정한다.
     *
     * @return 이번 실패로 새로 잠금 처리되었으면 true
     */
    public boolean signinFailure(int maxFailedAttempts, LocalDateTime now) {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= maxFailedAttempts) {
            if (this.status == UserStatus.ACTIVE) {
                this.status = UserStatus.LOCKED;
                this.authVersion++;
                return true;
            }
        }
        return false;
    }

    /** 비밀번호 변경: 해시 교체 + 변경 시각 기록. */
    public void changePassword(String newPasswordHash, LocalDateTime now) {
        this.authVersion++;
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = now;
    }

    /**
     * 이메일 인증 기반 비밀번호 재설정: 해시 교체 + 실패 카운터 초기화.
     * LOCKED 계정의 복구 경로이기도 하다(잠금 해제). SUSPENDED는 호출 전에 거절해야 한다.
     */
    public void resetPassword(String newPasswordHash, LocalDateTime now) {
        changePassword(newPasswordHash, now);
        this.failedLoginAttempts = 0;
        if (this.status == UserStatus.LOCKED) {
            this.status = UserStatus.ACTIVE;
        }
    }

    // ── 관리자(대표) 상태 전이. 전이 불가 상태는 IllegalStateException(409)으로 던진다. ──

    /** 가입 승인: PENDING → ACTIVE + 실제 역할 지정. */
    public void approve(UserRole role) {
        if (this.status != UserStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태의 계정만 승인할 수 있습니다.");
        }
        this.role = role;
        this.status = UserStatus.ACTIVE;
    }

    /** 역할 변경. 기존 토큰은 무효화되어 다음 요청부터 재로그인이 필요하다. */
    public void changeRole(UserRole role) {
        if (this.status == UserStatus.PENDING) {
            throw new IllegalStateException("승인 대기 계정은 승인하면서 역할을 지정하세요.");
        }
        if (this.role == role) {
            throw new IllegalArgumentException("이미 해당 역할입니다.");
        }
        this.role = role;
        this.authVersion++;
    }

    /** 계정 정지. PENDING 계정에 쓰면 가입 거절의 의미가 된다. */
    public void suspend() {
        if (this.status == UserStatus.SUSPENDED) {
            throw new IllegalStateException("이미 정지된 계정입니다.");
        }
        this.status = UserStatus.SUSPENDED;
        this.authVersion++;
    }

    /** 정지 해제: SUSPENDED → ACTIVE. */
    public void unsuspend() {
        if (this.status != UserStatus.SUSPENDED) {
            throw new IllegalStateException("정지 상태의 계정이 아닙니다.");
        }
        this.status = UserStatus.ACTIVE;
        this.failedLoginAttempts = 0;
    }

    /** 잠금 해제: LOCKED → ACTIVE (비밀번호 재설정 없이 대표가 풀어주는 경로). */
    public void unlock() {
        if (this.status != UserStatus.LOCKED) {
            throw new IllegalStateException("잠금 상태의 계정이 아닙니다.");
        }
        this.status = UserStatus.ACTIVE;
        this.failedLoginAttempts = 0;
    }
}
