package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.MemorandumEntity;

import java.util.List;

@Repository
public interface MemorandumDao extends BaseMapper<MemorandumEntity> {

    @Select("SELECT * FROM memorandum WHERE c2_record_id = #{c2RecordId}")
    List<MemorandumEntity> findByC2RecordId(@Param("c2RecordId") Integer c2RecordId);

    @Update("UPDATE memorandum SET content=#{content}, content_hash=#{contentHash} WHERE id=#{id}")
    int updateContentById(@Param("id") Integer id,
                          @Param("content") String content,
                          @Param("contentHash") String contentHash);
}
