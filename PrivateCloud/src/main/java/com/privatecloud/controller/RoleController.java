package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.RoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ResultResponse<?> listRoles() {
        return ResultResponse.success(roleService.listRoles());
    }
}
