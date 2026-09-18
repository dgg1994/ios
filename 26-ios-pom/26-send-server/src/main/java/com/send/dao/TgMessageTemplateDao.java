package com.send.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.send.entity.TgMessageTemplateEntity;

@Mapper
public interface TgMessageTemplateDao extends BaseMapper<TgMessageTemplateEntity> {
}
