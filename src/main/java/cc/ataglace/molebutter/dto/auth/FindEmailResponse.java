package cc.ataglace.molebutter.dto.auth;

/** 이메일 찾기 결과. 전체 주소 노출을 피하려고 local part를 마스킹해 내려준다. */
public record FindEmailResponse(String maskedEmail) {

}
