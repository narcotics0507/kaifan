package com.scaffold.modules.h5;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.extra.qrcode.QrCodeUtil;
import com.scaffold.common.result.Result;
import com.scaffold.modules.table.service.DiningTableService;
import com.scaffold.modules.table.vo.DiningTableVO;
import com.scaffold.modules.order.vo.OrderVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import java.util.*;

@RestController @RequiredArgsConstructor
public class H5Controller {
    private final H5Service service;
    private final DiningTableService tables;
    public record Entry(@NotBlank @Size(max=50) String tableCode,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String key) {}
    public record Submit(@NotNull Long tableId,@NotBlank @Size(max=64) String sessionCode,
        @NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,64}") String requestId,@Size(max=500) String remark) {}
    @PostMapping("/app/h5/session")
    public Result<Map<String,Object>> session(HttpServletRequest req,HttpServletResponse res) {
        return Result.success(service.session(req,res));
    }
    @GetMapping("/app/h5/context") public Result<Map<String,Object>> context(){return Result.success(service.context());}
    @PostMapping("/app/h5/join") public Result<DiningTableVO> join(@Valid @RequestBody Entry dto){return Result.success(service.join(dto.tableCode(),dto.key()));}
    @PostMapping("/app/h5/submit") public Result<OrderVO> submit(@Valid @RequestBody Submit dto){return Result.success(service.submit(dto.tableId(),dto.sessionCode(),dto.requestId(),dto.remark()));}
    @GetMapping("/admin/h5/table-links") @SaCheckPermission("table:qrcode:generate")
    public Result<List<Map<String,Object>>> links(){
        service.enabled();return Result.success(tables.list().stream().map(t->Map.<String,Object>of("id",t.getId(),"code",t.getCode(),"name",t.getName(),"url",service.link(t))).toList());
    }
    @GetMapping(value="/admin/h5/table/{id}/qrcode",produces=MediaType.IMAGE_PNG_VALUE) @SaCheckPermission("table:qrcode:download")
    public byte[] qr(@PathVariable Long id){var table=tables.getById(id);if(table==null)throw new com.scaffold.common.exception.BusinessException(com.scaffold.common.result.ResultCode.NOT_FOUND);return QrCodeUtil.generatePng(service.link(table),600,600);}
}
