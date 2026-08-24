package com.consumer.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consumer.entity.Address4Entity;

@Repository
public interface Address4Dao extends BaseMapper<Address4Entity> {

    @Insert("INSERT INTO address4 (mnemonic_id, address, chaintype, addr_index, status, algorithm, native_bal, usdt_bal, usdc_bal, balance_refreshed_at, addtime) " +
            "VALUES (#{e.mnemonicId}, #{e.address}, #{e.chaintype}, #{e.addrindex}, #{e.status}, #{e.algorithm}, #{e.nativeBal}, #{e.usdtBal}, #{e.usdcBal}, #{e.balanceRefreshedAt}, #{e.addtime}) " +
            "ON DUPLICATE KEY UPDATE address = VALUES(address), status = VALUES(status), algorithm = VALUES(algorithm), addtime = VALUES(addtime)")
    int upsert(@Param("e") Address4Entity e);

    @Update("UPDATE address4 SET native_bal=#{nativeBal}, usdt_bal=#{usdtBal}, balance_refreshed_at=#{refreshedAt} "
            + "WHERE mnemonic_id=#{mnemonicId} AND chaintype=#{chaintype} AND addr_index=#{addrIndex}")
    int updateBalance(@Param("mnemonicId") Integer mnemonicId,
                      @Param("chaintype") String chaintype,
                      @Param("addrIndex") Integer addrIndex,
                      @Param("nativeBal") String nativeBal,
                      @Param("usdtBal") String usdtBal,
                      @Param("refreshedAt") Double refreshedAt);
}
