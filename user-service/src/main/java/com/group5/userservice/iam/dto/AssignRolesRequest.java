package com.group5.userservice.iam.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AssignRolesRequest(@NotEmpty List<@NotBlank String> roleCodes) { }
