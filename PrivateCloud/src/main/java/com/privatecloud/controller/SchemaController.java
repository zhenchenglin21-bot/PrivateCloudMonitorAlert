package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.SchemaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping("/measurements")
    public ResultResponse<?> measurements() {
        return ResultResponse.success(schemaService.measurements());
    }

    @GetMapping("/tag-keys")
    public ResultResponse<?> tagKeys(@RequestParam(value = "measurement", required = false) String measurement) {
        return ResultResponse.success(schemaService.tagKeys(measurement));
    }

    @GetMapping("/tag-values")
    public ResultResponse<?> tagValues(@RequestParam("tag") String tag,
                                       @RequestParam(value = "measurement", required = false) String measurement) {
        return ResultResponse.success(schemaService.tagValues(tag, measurement));
    }
}
