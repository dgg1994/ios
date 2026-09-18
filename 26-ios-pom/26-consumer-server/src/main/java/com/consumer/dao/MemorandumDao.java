package com.consumer.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.MemorandumEntity;

@Mapper
public interface MemorandumDao extends BaseMapper<MemorandumEntity> {
}
