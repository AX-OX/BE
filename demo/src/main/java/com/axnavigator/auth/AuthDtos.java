package com.axnavigator.auth;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public final class AuthDtos {
    private AuthDtos() {}

    public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "올바른 이메일 형식을 입력해 주세요.")
        @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
        @Schema(example = "newuser@gaon.example") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @Pattern(regexp = "(?s)(?=.*[A-Za-z])(?=.*[0-9]).*", message = "비밀번호는 영문과 숫자를 포함해야 합니다.")
        @Schema(example = "ChangeMe123!", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
        @NotBlank(message = "이름을 입력해 주세요.") @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        @Schema(example = "홍길동") String name) {
        @JsonIgnore
        @AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
        public boolean isPasswordWithinBcryptLimit() {
            return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }

    public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "올바른 이메일 형식을 입력해 주세요.")
        @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
        @Schema(example = "analyst1@gaon.example") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Schema(example = "ChangeMe123!", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {}

    public record SignupResponse(Long userId) {}
    public record UserResponse(Long userId, String name, Long companyId) {}
    public record LoginResponse(String accessToken, UserResponse user) {}
}
