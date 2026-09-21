package cc.ataglace.molebutter.dto.admin;

import cc.ataglace.molebutter.domain.UserRole;
import jakarta.validation.constraints.NotNull;

/** 가입 승인 — 승인과 동시에 실제 역할(직원 종류)을 지정한다. */
public record UserApproveRequest(
        @NotNull UserRole role) {

}
