package cc.ataglace.molebutter.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FindEmailRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 20) String phoneNumber) {

}
