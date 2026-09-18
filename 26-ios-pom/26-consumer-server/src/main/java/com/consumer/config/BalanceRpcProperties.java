package com.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 查链 RPC，对齐 18 {@code monitor.balance}：NOWNodes 优先，失败降级官方/公共节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "monitor.balance")
public class BalanceRpcProperties {

    private boolean enabled = true;
    private int timeoutMs = 8000;
    private int connectTimeoutMs = 5000;
    private int maxConcurrent = 24;
    /** NOWNodes 失败后再试几个公共节点；0 表示只打主节点 */
    private int maxFallbackNodes = 1;
    private int poolSize = 24;
    private String ethRpc = "https://ethereum.publicnode.com,https://1rpc.io/eth,https://cloudflare-eth.com,https://eth.llamarpc.com";
    private String bscRpc = "https://bsc-dataseed.binance.org,https://bsc-dataseed1.binance.org,https://bsc.publicnode.com,https://bsc-dataseed2.binance.org";
    private String tronApi = "https://api.trongrid.io";
    private String btcApi = "https://blockstream.info/api,https://mempool.space/api";
    private String solRpc = "https://api.mainnet-beta.solana.com,https://solana-rpc.publicnode.com,https://rpc.ankr.com/solana";
    private String ethUsdt = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    private String bscUsdt = "0x55d398326f99059fF775485246999027B3197955";
    private String tronUsdt = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    private String solUsdt = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private NowNodes nownodes = new NowNodes();

    @Data
    public static class NowNodes {
        /** true 且 api-key 非空才走三方 */
        private boolean enabled = true;
        private String apiKey = "";
        private String eth = "https://eth.nownodes.io";
        private String bsc = "https://bsc.nownodes.io";
        private String sol = "https://sol.nownodes.io";
        /** Blockbook GET /api/v2/address/{addr} */
        private String btc = "https://btcbook.nownodes.io";
        /** Tron Blockbook GET /api/v2/address/{addr}?details=tokenBalances */
        private String tron = "https://trx-blockbook.nownodes.io";
    }
}
