package com.binding.service;

import com.binding.entity.C2RecordsEntity;

/**
 * c2_records 入库服务。
 */
public interface C2RecordService {

    /**
     * 入库 c2_records。
     */
    void recordTwo(C2RecordsEntity record, String bodyText, String domain, String ip);
}
