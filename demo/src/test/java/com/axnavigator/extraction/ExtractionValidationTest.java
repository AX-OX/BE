package com.axnavigator.extraction;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.axnavigator.ax.calc.DataConfidence;
import com.axnavigator.common.ApiException;
import com.axnavigator.company.*;
import com.axnavigator.extraction.ExtractionDtos.*;
import static org.assertj.core.api.Assertions.*;

class ExtractionValidationTest {
    static final ExtractionDtos.Department HR = new ExtractionDtos.Department("d1", "인사팀");
    static final ExtractionDtos.Process FAQ = new ExtractionDtos.Process("p1", "d1", "규정 문의 응답", null, null, null, false, null, WorkType.KNOWLEDGE_QA, null);
    static Metric metric(String key) { return new Metric(key, 10, BigDecimal.TEN, 30000, null, DataSourceType.EXTRACTED, null); }

    @Test void acceptsConsistentKeys() {
        var system = new SystemInfo("s1", "그룹웨어", SystemType.GROUPWARE, true, DataAccessLevel.OPEN, true, List.of("p1"));
        assertThatNoException().isThrownBy(() -> ExtractionService.validate(new Payload(null, List.of(HR), List.of(FAQ), List.of(metric("p1")), List.of(system))));
    }

    @Test void rejectsDuplicateKeysAndBrokenReferences() {
        assertThatThrownBy(() -> ExtractionService.validate(new Payload(null, List.of(HR, HR), List.of(), List.of(), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("부서 key d1");
        assertThatThrownBy(() -> ExtractionService.validate(new Payload(null, List.of(HR), List.of(FAQ), List.of(metric("p1"), metric("p1")), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("지표가 중복");
        assertThatThrownBy(() -> ExtractionService.validate(new Payload(null, List.of(HR), List.of(FAQ), List.of(metric("p9")), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("p9");
        var orphan = new SystemInfo("s1", "ERP", SystemType.ERP, false, DataAccessLevel.NONE, false, List.of("p9"));
        assertThatThrownBy(() -> ExtractionService.validate(new Payload(null, List.of(HR), List.of(FAQ), List.of(), List.of(orphan))))
            .isInstanceOf(ApiException.class).hasMessageContaining("시스템 s1");
    }

    @Test void userInputCountsAsSurveyAndExtractedSitsAboveAssumed() {
        assertThat(DataSourceType.USER_INPUT.confidence()).isEqualTo(DataConfidence.SURVEY);
        assertThat(DataSourceType.EXTRACTED.confidence()).isGreaterThan(DataConfidence.ASSUMED).isLessThan(DataConfidence.SURVEY);
        assertThat(DataSourceType.MEASURED.confidence()).isEqualTo(DataConfidence.MEASURED);
    }
}
