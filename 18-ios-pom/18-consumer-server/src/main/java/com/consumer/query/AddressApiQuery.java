package com.consumer.query;

import java.util.List;

import lombok.Data;

@Data
public class AddressApiQuery {
	
	private String sign;
	
	private String chain;
	
	private List<String> addresses;

}
