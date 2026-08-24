package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.Ios18ParamEntity;

@Repository
public interface Ios18ParamDao extends BaseMapper<Ios18ParamEntity> {

    @Select("SELECT * FROM ios18param WHERE id = #{id}")
    Ios18ParamEntity findById(@Param("id") Integer id);

    @Update("UPDATE ios18param SET unpack_path=#{unpackPath}, remark=#{remark} WHERE id=#{id}")
    int updateUnpack(@Param("id") Integer id,
                     @Param("unpackPath") String unpackPath,
                     @Param("remark") String remark);

    @Update("UPDATE ios18param SET ci_result=#{ciResult}, remark=#{remark} WHERE id=#{id}")
    int updateParseCi(@Param("id") Integer id,
                      @Param("ciResult") String ciResult,
                      @Param("remark") String remark);
}
