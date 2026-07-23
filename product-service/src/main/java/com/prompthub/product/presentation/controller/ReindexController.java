package com.prompthub.product.presentation.controller;

import com.prompthub.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/search")
public class ReindexController {

	private final ProductReindexService productReindexService;

	@PostMapping("/reindex")
	public void reindex() {
		productReindexService.reindexAll();
	}
}
