package com.axnavigator.recovery;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Component
@ConfigurationProperties("ax.recovery")
@Validated
@Getter
@Setter
public class RecoveryProperties {
    private boolean enabled;
    @Min(30) @Max(600) private int leaseSeconds = 90;
    @Min(1) @Max(20) private int maxAttempts = 5;
    @Min(1) @Max(72) private int inputRetentionHours = 24;
    @Min(1) private int commandRetentionDays = 7;
}
