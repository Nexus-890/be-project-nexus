package com.group5.common_core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL) // field nào null sẽ ko gửi lên
public class APIResponse<T>{

    int code;
    String message;
    T result;

    public static <T> APIResponse<T> response(int code, String message, T result) {
        return APIResponse.<T>builder()
                .code(code)
                .message(message)
                .result(result)
                .build();
    }



}
