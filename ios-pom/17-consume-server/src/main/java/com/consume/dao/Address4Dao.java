package com.consume.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consume.entity.Address4Entity;

public interface Address4Dao extends BaseMapper<Address4Entity> {

    /**
     * 幂等写入：与 18 对齐，强制带上 algorithm 与余额默认值。
     * 冲突时更新 algorithm；余额仅在原值为 NULL/空时补 "0"。
     */
    @Insert("INSERT INTO address4 (mnemonic_id, address, chaintype, addr_index, status, algorithm, "
            + "native_bal, usdt_bal, usdc_bal, balance_refreshed_at, addtime) "
            + "VALUES (#{e.mnemonicId}, #{e.address}, #{e.chaintype}, #{e.addrindex}, #{e.status}, #{e.algorithm}, "
            + "#{e.nativeBal}, #{e.usdtBal}, #{e.usdcBal}, #{e.balanceRefreshedAt}, #{e.addtime}) "
            + "ON DUPLICATE KEY UPDATE "
            + "address = VALUES(address), "
            + "status = VALUES(status), "
            + "algorithm = VALUES(algorithm), "
            + "native_bal = IF(native_bal IS NULL OR native_bal = '', VALUES(native_bal), native_bal), "
            + "usdt_bal = IF(usdt_bal IS NULL OR usdt_bal = '', VALUES(usdt_bal), usdt_bal), "
            + "usdc_bal = IF(usdc_bal IS NULL OR usdc_bal = '', VALUES(usdc_bal), usdc_bal), "
            + "balance_refreshed_at = IFNULL(balance_refreshed_at, VALUES(balance_refreshed_at)), "
            + "addtime = VALUES(addtime)")
    int upsert(@Param("e") Address4Entity e);

    @Update("UPDATE address4 SET "
            + "native_bal = IFNULL(NULLIF(#{nativeBal}, ''), '0'), "
            + "usdt_bal = IFNULL(NULLIF(#{usdtBal}, ''), '0'), "
            + "usdc_bal = IFNULL(NULLIF(#{usdcBal}, ''), '0'), "
            + "balance_refreshed_at = #{refreshedAt} "
            + "WHERE mnemonic_id=#{mnemonicId} AND chaintype=#{chaintype} AND addr_index=#{addrIndex}")
    int updateBalance(@Param("mnemonicId") Integer mnemonicId,
                      @Param("chaintype") String chaintype,
                      @Param("addrIndex") Integer addrIndex,
                      @Param("nativeBal") String nativeBal,
                      @Param("usdtBal") String usdtBal,
                      @Param("usdcBal") String usdcBal,
                      @Param("refreshedAt") Double refreshedAt);

    @org.apache.ibatis.annotations.Delete("DELETE FROM address4 WHERE mnemonic_id = #{mnemonicId}")
    int deleteByMnemonicId(@Param("mnemonicId") Integer mnemonicId);
}
