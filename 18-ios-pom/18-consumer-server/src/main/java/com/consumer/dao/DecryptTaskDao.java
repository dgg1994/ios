package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.DecryptTaskEntity;

/**
 * 待爆破加密材料表 waitbound。
 * <p>约定：一设备一钱包一条；{@code hex_content} 为钱包目录绝对路径。
 */
@Repository
public interface DecryptTaskDao extends BaseMapper<DecryptTaskEntity> {

    /**
     * 幂等：同设备 + 钱包已有一条则跳过插入。
     */
    @Select("SELECT id FROM waitbound WHERE device_id = #{deviceId} "
            + "AND wallet_name = #{walletName} LIMIT 1")
    Integer findIdByDeviceWallet(@Param("deviceId") String deviceId,
                                 @Param("walletName") String walletName);

    /**
     * 把已有行的 hex_content 更新为钱包目录（兼容旧「指文件」数据）。
     */
    @org.apache.ibatis.annotations.Update("UPDATE waitbound SET hex_content = #{hexContent}, "
            + "update_time = UNIX_TIMESTAMP() WHERE id = #{id}")
    int updateHexContentById(@Param("id") Integer id,
                             @Param("hexContent") String hexContent);
}
