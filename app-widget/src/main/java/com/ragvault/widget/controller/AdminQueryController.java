package com.ragvault.widget.controller;

import com.ragvault.widget.service.QueryRouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 어드민 text-to-sql 테스트 콘솔 API.
 * /admin/query
 *
 * JWT 인증된 운영자가 자연어 질문을 보내면 QueryRouterService 가
 * RAG / SQL / HYBRID 경로로 분기해 응답한다.
 */
@RestController
@RequestMapping("/api/admin/query")
@RequiredArgsConstructor
public class AdminQueryController {

    private final QueryRouterService queryRouterService;

    @PostMapping
    public QueryResponse query(@RequestBody QueryRequest req, Authentication authentication) {
        String userEmail = authentication != null ? authentication.getName() : "admin";
        QueryRouterService.RouterResult result =
                queryRouterService.route(req.message(), List.of(), userEmail, "admin-console", null);
        return new QueryResponse(result.content(), result.intent(), result.generatedSql());
    }

    record QueryRequest(String message) {}

    record QueryResponse(String content, String intent, String generatedSql) {}
}
