package com.consume.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.consume.dto.AddressApiQuery;
import com.consume.util.MonitorUtil;
import com.consume.util.WalletDerivator;

/**
 * 助记词派生地址后，异步通知三方 /listen/addaddress。
 * 事务提交后再投递；HTTP 在独立线程池执行，失败只打日志。
 */
@Component
public class AddressListenNotifier {

    private static final Logger log = LoggerFactory.getLogger(AddressListenNotifier.class);

    private static final String ADD_ADDRESS_PATH = "/listen/addaddress";

    @Autowired
    @Qualifier("monitorListenExecutor")
    private Executor monitorListenExecutor;

    @Value("${monitor.listen.enabled:true}")
    private boolean enabled;

    /**
     * 按链分组后异步上报；在事务提交后执行，避免回滚后误注册。
     */
    public void notifyAfterCommit(Integer mnemonicId, List<WalletDerivator.DerivedAddress> addresses) {
        if (!enabled || addresses == null || addresses.isEmpty()) {
            return;
        }
        Map<String, List<String>> byChain = groupByChain(addresses);
        if (byChain.isEmpty()) {
            return;
        }
        Runnable task = () -> submitAll(mnemonicId, byChain);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    monitorListenExecutor.execute(task);
                }
            });
            return;
        }
        monitorListenExecutor.execute(task);
    }

    private void submitAll(Integer mnemonicId, Map<String, List<String>> byChain) {
        for (Map.Entry<String, List<String>> ne : byChain.entrySet()) {
            try {
                AddressApiQuery query = new AddressApiQuery();
                query.setChain(ne.getKey());
                query.setAddresses(ne.getValue());
                MonitorUtil.walletPost(ADD_ADDRESS_PATH, query);
                log.info("正常日志:[monitor] addaddress 已提交, mnemonicId={}, chain={}, count={}",
                        mnemonicId, ne.getKey(), ne.getValue().size());
            } catch (Exception e) {
                log.info("异常日志:[monitor] addaddress 失败, mnemonicId={}, chain={}, err={}",
                        mnemonicId, ne.getKey(), e.getMessage());
            }
        }
    }

    private static Map<String, List<String>> groupByChain(List<WalletDerivator.DerivedAddress> addresses) {
        Map<String, List<String>> byChain = new LinkedHashMap<>();
        for (WalletDerivator.DerivedAddress da : addresses) {
            if (da == null || da.chaintype == null || da.chaintype.isEmpty()
                    || da.address == null || da.address.isEmpty()) {
                continue;
            }
            byChain.computeIfAbsent(da.chaintype, k -> new ArrayList<>()).add(da.address);
        }
        return byChain;
    }
}
