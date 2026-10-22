package com.axnavigator.common;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class EvaluationAsOfTest {
    /** 빈 AX_EVALUATION_AS_OF는 evaluation 자체를 만들지 않으므로 AxProperties에서 null을 허용해야 한다. */
    @Test void emptyAsOfIsUnboundAndExplicitDateIsKept() {
        var empty = new Binder(new MapConfigurationPropertySource(Map.of("ax.evaluation.as-of", ""))).bind("ax.evaluation", AxProperties.Evaluation.class);
        assertThat(empty.isBound()).isFalse();
        var fixed = new Binder(new MapConfigurationPropertySource(Map.of("ax.evaluation.as-of", "2026-10-01"))).bind("ax.evaluation", AxProperties.Evaluation.class);
        assertThat(fixed.get().asOf()).isEqualTo(LocalDate.of(2026, 10, 1));
    }
}
