package com.consumer.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.MnemonicEntity;

@Mapper
public interface MnemonicDao extends BaseMapper<MnemonicEntity> {
}
