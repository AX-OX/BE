package com.axnavigator.internal;

import org.springframework.web.bind.annotation.*;
import com.axnavigator.extraction.ExtractionService;
import com.axnavigator.extraction.ExtractionDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/extractions")
@Tag(name = "추출 결과 저장", description = "Agent의 기업 데이터 추출 결과 저장")
@SecurityRequirement(name = "InternalToken")
public class InternalExtractionController {
    private final ExtractionService extractions;
    public InternalExtractionController(ExtractionService extractions) { this.extractions = extractions; }
    @PostMapping
    @Operation(summary = "추출 결과 저장", description = "RUNNING 작업의 추출 결과를 저장합니다. 같은 작업의 재시도는 덮어씁니다. documentIds는 기업의 색인 완료 문서여야 합니다. 저장 후 CONFIRMATION_REQUIRED 이벤트를 보내면 작업이 WAITING_CONFIRMATION이 됩니다. 이미 확정된 기업은 409입니다.")
    public Saved save(@Valid @RequestBody SaveRequest request) { return extractions.save(request); }
}
