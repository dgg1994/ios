package com.send.web.v2;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.send.dto.ApiResponse;
import com.send.service.V2UploadService;
import com.send.util.BizException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v2")
public class V2UploadsController {

    @Autowired
    private V2UploadService v2UploadService;

    @PostMapping(value = "/uploads", consumes = {
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.ALL_VALUE
    })
    public Map<String, Object> upload(
            HttpServletRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String xDeviceId,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "file_name", required = false) String fileNameAlt) throws Exception {
        byte[] body = readBody(request);
        String contentType = StringUtils.defaultString(request.getContentType());
        JsonInit init = parseInitJson(body, contentType);
        String qName = StringUtils.trimToEmpty(
                StringUtils.defaultIfBlank(fileName, StringUtils.defaultIfBlank(fileNameAlt, init.fileName)));
        byte[] fileBytes = init.isJson ? new byte[0] : body;

        try {
            Map<String, Object> result;
            if (StringUtils.isNotBlank(qName)) {
                if (init.fileSize != null && init.fileSize < 0) {
                    throw new BizException(400, "fileSize 非法");
                }
                String deviceId = v2UploadService.resolveDeviceId(xDeviceId, qName);
                result = v2UploadService.createPlainUpload(qName, deviceId, fileBytes, init.fileSize);
            } else if (init.isJson || body.length == 0) {
                throw new BizException(400, "需要 fileName");
            } else {
                String name = "upload_v2_" + System.currentTimeMillis() + ".bin";
                if (request instanceof MultipartHttpServletRequest) {
                    MultipartFile file = ((MultipartHttpServletRequest) request).getFile("file");
                    if (file != null && StringUtils.isNotBlank(file.getOriginalFilename())) {
                        name = file.getOriginalFilename();
                    }
                }
                String deviceId = v2UploadService.resolveDeviceId(xDeviceId, name);
                result = v2UploadService.createPlainUpload(name, deviceId, body, null);
            }
            return ApiResponse.v2OkUpload(result);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("upload_v2 失败", e);
            throw new BizException(500, "upload failed");
        }
    }

    @PostMapping(value = "/uploads/{uploadId}/chunks", consumes = {
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.ALL_VALUE
    })
    public Map<String, Object> chunk(
            @PathVariable String uploadId,
            HttpServletRequest request,
            @RequestParam(value = "chunkIndex", required = false) Integer chunkIndex,
            @RequestParam(value = "chunk_index", required = false) Integer chunkIndexAlt) throws Exception {
        byte[] body = readBody(request);
        Integer index = chunkIndex != null ? chunkIndex : chunkIndexAlt;
        try {
            Map<String, Object> result = v2UploadService.appendPlainChunk(uploadId, body, index);
            return ApiResponse.v2OkUpload(result);
        } catch (BizException e) {
            log.warn("v2 uploads chunk 400 uploadId={} err={}", uploadId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("upload_v2 chunk 失败", e);
            throw new BizException(500, "upload failed");
        }
    }

    private byte[] readBody(HttpServletRequest request) throws Exception {
        String contentType = StringUtils.defaultString(request.getContentType()).toLowerCase();
        if (contentType.contains("multipart/form-data") && request instanceof MultipartHttpServletRequest) {
            MultipartFile file = ((MultipartHttpServletRequest) request).getFile("file");
            if (file != null) {
                return file.getBytes();
            }
            return new byte[0];
        }
        return request.getInputStream().readAllBytes();
    }

    private JsonInit parseInitJson(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return JsonInit.none();
        }
        String ctype = StringUtils.defaultString(contentType).toLowerCase();
        boolean looksJson = ctype.contains("application/json") || startsWithBrace(body);
        if (!looksJson) {
            return JsonInit.none();
        }
        try {
            JSONObject data = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            if (data == null) {
                return JsonInit.none();
            }
            String name = StringUtils.trimToNull(data.getString("fileName"));
            if (name == null) {
                name = StringUtils.trimToNull(data.getString("file_name"));
            }
            Long size = null;
            if (data.containsKey("fileSize") || data.containsKey("file_size")) {
                size = data.getLong("fileSize");
                if (size == null) {
                    size = data.getLong("file_size");
                }
            }
            return new JsonInit(name, size, true);
        } catch (Exception e) {
            return JsonInit.none();
        }
    }

    private boolean startsWithBrace(byte[] body) {
        for (byte b : body) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{';
        }
        return false;
    }

    private static final class JsonInit {
        final String fileName;
        final Long fileSize;
        final boolean isJson;

        JsonInit(String fileName, Long fileSize, boolean isJson) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.isJson = isJson;
        }

        static JsonInit none() {
            return new JsonInit(null, null, false);
        }
    }
}
