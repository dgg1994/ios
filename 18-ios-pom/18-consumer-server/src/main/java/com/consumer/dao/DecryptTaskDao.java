package com.consumer.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.DecryptTaskEntity;

/**
 * 待爆破加密材料表 waitbound。
 */
@Repository
public interface DecryptTaskDao extends BaseMapper<DecryptTaskEntity> {

    /**
     * 幂等：同设备 + 钱包已有一条则跳过（一钱包一条）。
     */
    @Select("SELECT id FROM waitbound WHERE device_id = #{deviceId} "
            + "AND wallet_name = #{walletName} LIMIT 1")
    Integer findIdByDeviceWallet(@Param("deviceId") String deviceId,
                                 @Param("walletName") String walletName);

    /**
     * @deprecated 旧幂等（按 encrypt_type+hash）；保留兼容，新逻辑用 {@link #findIdByDeviceWallet}
     */
    @Select("SELECT id FROM waitbound WHERE device_id = #{deviceId} "
            + "AND wallet_name = #{walletName} AND encrypt_type = #{encryptType} LIMIT 1")
    Integer findIdByDeviceWalletEncrypt(@Param("deviceId") String deviceId,
                                        @Param("walletName") String walletName,
                                        @Param("encryptType") String encryptType);
}
