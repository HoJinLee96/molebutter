package cc.ataglace.molebutter.identity.api;


import cc.ataglace.molebutter.identity.api.UserRole;
import jakarta.validation.constraints.NotNull;

/** 가입 승인 — 승인과 동시에 실제 역할(직원 종류)을 지정한다. */
public record UserApproveRequest(
        @NotNull UserRole role) {

}
