package com.report.enums;

import java.util.ArrayList;
import java.util.List;

/**
 * @category 语言
 * @author Hlin
 *
 */
public enum LanguageEnums {
	ZH_CN(1, "zh-cn", "zh-cn", "中文简体"),
	EN_US(2, "en", "en", "英文"),
	ZH_TW(3, "zh-hk", "zh-hk", "中文繁体"),
	TR(4, "tr-tr", "tr-tr", "土耳其"),
	KO(5, "ko-kr", "ko-kr", "韩语"),
	JA(6, "ja-jp", "ja-jp", "日语"),
	PT_BR(6, "pt-br", "pt-br", "巴西语");
	

	private Integer index;

	private String name;

	private String value;
	
	private String lable;

	private LanguageEnums(Integer index, String name, String value,String lable) {
		this.index = index;
		this.name = name;
		this.value = value;
		this.lable = lable;
	}

	public Integer getIndex() {
		return index;
	}

	public void setIndex(Integer index) {
		this.index = index;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
	

	public String getLable() {
		return lable;
	}

	public void setLable(String lable) {
		this.lable = lable;
	}

	public static String getName(int i) {
		LanguageEnums[] typeEnums = values();
		for (LanguageEnums typeEnum : typeEnums) {
			if (typeEnum.getIndex().equals(i)) {
				return typeEnum.getName();
			}
		}
		return null;
	}
	
	public static String getLable(String name) {
		LanguageEnums[] typeEnums = values();
		for (LanguageEnums typeEnum : typeEnums) {
			if (typeEnum.getName().equals(name)) {
				return typeEnum.getLable();
			}
		}
		return null;
	}
	
	public static List<String> getAll() {
		LanguageEnums[] typeEnums = values();
		List<String> list = new ArrayList<>();
		for (LanguageEnums typeEnum : typeEnums) {
			list.add(typeEnum.getName());
		}
		return list;
	}

}
