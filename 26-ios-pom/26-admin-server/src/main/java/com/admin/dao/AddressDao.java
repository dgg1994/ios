package com.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import com.admin.entity.AddressEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AddressDao extends BaseMapper<AddressEntity> {
}
