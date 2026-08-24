package com.report.service;

import com.report.entity.C2RecordsEntity;

/**
 * c2_records 入库服务。
 */
public interface C2RecordService {

    /**
     * @param record 完整记录（除 id 必填）
     */
    void record(C2RecordsEntity record);
}
