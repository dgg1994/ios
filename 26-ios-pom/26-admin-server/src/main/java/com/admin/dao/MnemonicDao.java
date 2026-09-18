package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.MnemonicEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface MnemonicDao extends BaseMapper<MnemonicEntity> {
}
