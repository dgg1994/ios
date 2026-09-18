package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.MemorandumEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface MemorandumDao extends BaseMapper<MemorandumEntity> {
}
