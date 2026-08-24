package com.report.service;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/details")
public interface DetailsService {

	@GetMapping("/**")
	public ResponseEntity<Resource> getFile(HttpServletRequest request);

}
