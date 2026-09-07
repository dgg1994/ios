package com.orc.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MnemonicCheckResponse {

    /** 是否像助记词截图（通过则可上云） */
    private boolean pass;

    /** 判定原因码 */
    private String reason;

    /** OCR 全文（可能较长，默认截断返回） */
    private String text;

    /** BIP39 命中词数 */
    private int bip39Hits;

    /** OCR 原文长度 */
    private int ocrLen;

    /** 图片宽高 */
    private int width;
    private int height;

    /** 处理耗时 ms */
    private long costMs;

    /** OCR 引擎是否就绪 */
    private boolean ocrReady;
}
