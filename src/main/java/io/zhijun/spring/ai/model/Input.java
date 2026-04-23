package io.zhijun.spring.ai.model;

import jakarta.validation.constraints.NotBlank;

public record Input(@NotBlank String prompt) {
}
