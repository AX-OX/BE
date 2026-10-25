package com.axnavigator.chat;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.axnavigator.auth.CurrentUser;
import com.axnavigator.chat.ChatDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ai/chat/messages")
@Tag(name = "AI 대화", description = "질문에 답변하고 명확한 분석 실행 요청만 새 태스크로 연결합니다.")
@SecurityRequirement(name = "BearerAuth")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat = chat; }

    @PostMapping
    @Operation(summary = "메시지 의도에 따른 답변 또는 새 분석 접수",
        description = "최근 대화와 선택한 본인 작업을 참고합니다. 일반 답변은 taskId가 null이고, 실제 분석 접수 시에만 taskId가 반환됩니다. 대화 API는 자동 재전송하지 않습니다.")
    public Reply send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        CurrentUser user = CurrentUser.from(jwt);
        return chat.send(user.userId(), user.companyId(), request);
    }
}
