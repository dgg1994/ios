package com.send.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import lombok.Data;

@Data
public class UploadCreateRequest {

    @NotBlank
    @Size(min = 1, max = 255)
    private String fileName;

    @NotNull
    @Min(0)
    private Long fileSize;
}
