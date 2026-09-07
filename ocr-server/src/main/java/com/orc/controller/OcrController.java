package com.orc.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.orc.dto.MnemonicCheckResponse;
import com.orc.service.MnemonicOcrService;

@RestController
@RequestMapping("/ocr")
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);

    private final MnemonicOcrService mnemonicOcrService;

    public OcrController(MnemonicOcrService mnemonicOcrService) {
        this.mnemonicOcrService = mnemonicOcrService;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok().body(
                java.util.Map.of(
                        "ok", true,
                        "ocrReady", mnemonicOcrService.isOcrReady()));
    }

    /**
     * 助记词截图判定：raw body = 图片字节。
     * Content-Type: application/octet-stream 或 image/*
     */
    @PostMapping(value = "/mnemonic-check", consumes = {
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/*"
    })
    public MnemonicCheckResponse mnemonicCheckRaw(@RequestBody byte[] body) {
        MnemonicCheckResponse r = mnemonicOcrService.check(body);
        log.info("正常日志:[ocr] mnemonic-check raw pass={} reason={} hits={} costMs={} bytes={}",
                r.isPass(), r.getReason(), r.getBip39Hits(), r.getCostMs(),
                body == null ? 0 : body.length);
        return r;
    }

    /**
     * 助记词截图判定：multipart 字段名 file。
     */
    @PostMapping(value = "/mnemonic-check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MnemonicCheckResponse mnemonicCheckMultipart(@RequestPart("file") MultipartFile file) throws Exception {
        byte[] body = file == null ? null : file.getBytes();
        MnemonicCheckResponse r = mnemonicOcrService.check(body);
        log.info("正常日志:[ocr] mnemonic-check multipart pass={} reason={} hits={} costMs={} name={}",
                r.isPass(), r.getReason(), r.getBip39Hits(), r.getCostMs(),
                file == null ? "" : file.getOriginalFilename());
        return r;
    }
}
