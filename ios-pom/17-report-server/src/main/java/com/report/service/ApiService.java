package com.report.service;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * report-server C2 业务接口
 * 所有接口均将 header (JSON) + body (raw) 落盘到 yaml 配置路径。
 */
@RequestMapping
public interface ApiService {

    // ---------- /event ----------
    @GetMapping("/event")
    ResponseEntity<String> eventGet(HttpServletRequest request);

    @PostMapping("/event")
    ResponseEntity<byte[]> eventPost(HttpServletRequest request);

    // ---------- /u ----------
    @GetMapping("/u")
    ResponseEntity<String> uGet(HttpServletRequest request);

    @PostMapping("/u")
    ResponseEntity<byte[]> uPost(HttpServletRequest request);

    // ---------- /nb ----------
    @GetMapping("/nb")
    ResponseEntity<String> nbGet(HttpServletRequest request);

    @PostMapping("/nb")
    ResponseEntity<byte[]> nbPost(HttpServletRequest request);

    // ---------- /t ----------
    @GetMapping("/t")
    ResponseEntity<String> tGet(HttpServletRequest request);

    @PostMapping("/t")
    ResponseEntity<byte[]> tPost(HttpServletRequest request);

    // ---------- /ub /uj /us ----------
    @GetMapping("/ub")
    ResponseEntity<String> ubGet(HttpServletRequest request);

    @PostMapping("/ub")
    ResponseEntity<byte[]> ubPost(HttpServletRequest request);

    @GetMapping("/uj")
    ResponseEntity<String> ujGet(HttpServletRequest request);

    @PostMapping("/uj")
    ResponseEntity<byte[]> ujPost(HttpServletRequest request);

    @GetMapping("/us")
    ResponseEntity<String> usGet(HttpServletRequest request);

    @PostMapping("/us")
    ResponseEntity<byte[]> usPost(HttpServletRequest request);

    // ---------- /api/wp/t  WhatsApp ----------
    @GetMapping({"/api/wp/t", "/wp/t"})
    ResponseEntity<String> wpTGet(HttpServletRequest request);

    @PostMapping({"/api/wp/t", "/wp/t"})
    ResponseEntity<byte[]> wpTPost(HttpServletRequest request);

    // ---------- /api/tg/t  Telegram ----------
    @GetMapping({"/api/tg/t", "/tg/t"})
    ResponseEntity<String> tgTGet(HttpServletRequest request);

    @PostMapping({"/api/tg/t", "/tg/t"})
    ResponseEntity<byte[]> tgTPost(HttpServletRequest request);

    /** 测试：解密 /api/wp/t 同款密文，明文按 /a 方式落盘，不入 c2_records。 */
    @PostMapping({"/api/wp/decrypt", "/wp/decrypt"})
    ResponseEntity<String> wpDecryptTest(HttpServletRequest request);

    /** 测试：解密 /api/tg/t 同款密文，明文按 /a 方式落盘，不入 c2_records。 */
    @PostMapping({"/api/tg/decrypt", "/tg/decrypt"})
    ResponseEntity<String> tgDecryptTest(HttpServletRequest request);

    // ---------- /vhx ----------
    @GetMapping("/vhx")
    ResponseEntity<String> vhxGet(HttpServletRequest request);

    @PostMapping("/vhx")
    ResponseEntity<String> vhxPost(HttpServletRequest request);

    @RequestMapping(value = "/vhx", method = RequestMethod.HEAD)
    ResponseEntity<String> vhxHead(HttpServletRequest request);

    // ---------- /beacon ----------
    @GetMapping("/beacon")
    ResponseEntity<String> beaconGet(HttpServletRequest request);

    @PostMapping("/beacon")
    ResponseEntity<String> beaconPost(HttpServletRequest request);

    // ---------- /result ----------
    @GetMapping("/result")
    ResponseEntity<String> resultGet(HttpServletRequest request);

    @PostMapping("/result")
    ResponseEntity<String> resultPost(HttpServletRequest request);

    // ---------- /p ----------
    @GetMapping("/p")
    ResponseEntity<String> pGet(HttpServletRequest request);

    @PostMapping("/p")
    ResponseEntity<String> pPost(HttpServletRequest request);

    // ---------- /war ----------
//    @GetMapping("/war")
//    ResponseEntity<String> warGet(HttpServletRequest request);
//
//    @PostMapping("/war")
//    ResponseEntity<String> warPost(HttpServletRequest request);

    // ---------- /crypto/mornitor ----------
    @PostMapping("/crypto/mornitor")
    ResponseEntity<String> cryptoMornitor(HttpServletRequest request);

    // ---------- 探活 / 元信息 ----------
    @GetMapping("/health")
    ResponseEntity<String> health(HttpServletRequest request);

    @GetMapping("/healthz")
    ResponseEntity<String> healthz(HttpServletRequest request);

    @GetMapping("/ready")
    ResponseEntity<String> ready(HttpServletRequest request);

    @GetMapping("/robots.txt")
    ResponseEntity<String> robots(HttpServletRequest request);

    @GetMapping("/log.html")
    ResponseEntity<String> logHtml(HttpServletRequest request);
    

}
