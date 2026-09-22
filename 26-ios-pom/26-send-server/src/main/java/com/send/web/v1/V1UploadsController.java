package com.send.web.v1;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.send.dto.ApiResponse;
import com.send.service.V1UploadService;
import com.send.util.BizException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/uploads")
public class V1UploadsController {

    @Autowired
    private V1UploadService v1UploadService;

    @PostMapping({"", "/"})
    public Map<String, Object> create(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestBody(required = false) byte[] body) {
        JSONObject data = body == null || body.length == 0 ? new JSONObject() : JSONObject.parseObject(new String(body));
        String fileName = data == null ? null : StringUtils.trimToNull(data.getString("fileName"));
        if (fileName == null && data != null) {
            fileName = StringUtils.trimToNull(data.getString("file_name"));
        }
        Long fileSize = null;
        if (data != null && (data.containsKey("fileSize") || data.containsKey("file_size"))) {
            fileSize = data.getLong("fileSize");
            if (fileSize == null) {
                fileSize = data.getLong("file_size");
            }
        }
        if (fileName == null) {
            throw new BizException(400, "缺少字段：fileName");
        }
        if (fileSize == null) {
            throw new BizException(400, "缺少字段：fileSize");
        }
        return ApiResponse.ok(v1UploadService.createSession(deviceId, fileName, fileSize));
    }

    @PostMapping(value = "/{uploadId}/chunks", consumes = {
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            MediaType.ALL_VALUE
    })
    public Map<String, Object> chunk(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @PathVariable String uploadId,
            @RequestParam(value = "chunkIndex", required = false) Integer chunkIndex,
            @RequestParam(value = "chunk_index", required = false) Integer chunkIndexAlt,
            HttpServletRequest request) throws Exception {
        Integer index = chunkIndex != null ? chunkIndex : chunkIndexAlt;
        if (index == null) {
            throw new BizException(400, "缺少 chunkIndex");
        }
        byte[] body = request.getInputStream().readAllBytes();
        try {
            return ApiResponse.ok(v1UploadService.saveChunk(deviceId, uploadId, index, body));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("v1 upload chunk 失败 uploadId={}", uploadId, e);
            throw new BizException(500, "upload failed");
        }
    }
}
