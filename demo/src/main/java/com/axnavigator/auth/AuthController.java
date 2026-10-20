package com.axnavigator.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.axnavigator.auth.AuthDtos.*;
import com.axnavigator.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증", description = "회원가입과 JWT 발급. 로그아웃은 프론트에서 토큰을 삭제합니다.")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회원가입", description = "기본 기업(1)에 사용자를 등록합니다. 비밀번호는 8자 이상 영문·숫자를 포함하며 BCrypt로 저장합니다. 가입 후 자동 로그인하지 않습니다. BCrypt 입력 한도는 UTF-8 72바이트입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "가입 성공", content = @Content(schema = @Schema(implementation = SignupResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 입력값 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "DUPLICATE_EMAIL: 이메일 중복", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) { return auth.signup(request); }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "이메일과 비밀번호를 확인하고 JWT와 사용자 정보를 반환합니다. 토큰 만료는 기본 12시간입니다. 이후 요청은 Authorization: Bearer 헤더를 사용하며 SSE도 동일합니다. 쿼리 토큰은 지원하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공", content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 입력값 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: 이메일 또는 비밀번호 불일치", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public LoginResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }
}
