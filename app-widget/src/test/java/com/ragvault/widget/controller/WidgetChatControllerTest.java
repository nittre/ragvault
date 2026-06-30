package com.ragvault.widget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.widget.dto.ChatMessage;
import com.ragvault.widget.dto.WidgetChatRequest;
import com.ragvault.core.service.JwtService;
import com.ragvault.widget.service.QueryRouterService;
import com.ragvault.widget.service.SearchConfigService;
import com.ragvault.widget.service.SiteKeyService;
import com.ragvault.widget.service.WidgetRagService;
import com.ragvault.widget.service.WidgetRagService.RagResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WidgetChatController MockMvc 단위 테스트.
 *
 * SiteKeyFilter 가 @WebMvcTest 슬라이스에 포함된다.
 * SiteKeyService 는 슬라이스 컨텍스트에 없으므로 @MockitoBean 으로 제공.
 * isValidKey("pk_test_widget_dev") → true, 그 외 → false 로 stubbing.
 */
@WebMvcTest(value = WidgetChatController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "widget.cors.allowed-origins=http://localhost:3000"
})
class WidgetChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WidgetRagService ragService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private SiteKeyService siteKeyService;

    @MockitoBean
    private SearchConfigService searchConfigService;

    @MockitoBean
    private QueryRouterService queryRouterService;

    @BeforeEach
    void stubSiteKeyService() {
        // 유효 키만 true, 그 외 default false (Mockito 기본값)
        when(siteKeyService.isValidKey("pk_test_widget_dev")).thenReturn(true);
    }

    @Test
    void chatSuccess() throws Exception {
        when(ragService.chat(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(RagResult.success("배송은 2~3일 소요됩니다.", List.of()));

        WidgetChatRequest request = new WidgetChatRequest(
                List.of(new ChatMessage("user", "배송 기간이 얼마나 걸리나요?"))
        );

        mockMvc.perform(post("/v1/widget/chat")
                        .header("X-Site-Key", "pk_test_widget_dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("배송은 2~3일 소요됩니다."))
                .andExpect(jsonPath("$.object").value("chat.completion"));
    }

    @Test
    void missingHeaderReturns401() throws Exception {
        WidgetChatRequest request = new WidgetChatRequest(
                List.of(new ChatMessage("user", "배송 기간이 얼마나 걸리나요?"))
        );

        mockMvc.perform(post("/v1/widget/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSiteKeyReturns401() throws Exception {
        WidgetChatRequest request = new WidgetChatRequest(
                List.of(new ChatMessage("user", "배송 기간이 얼마나 걸리나요?"))
        );

        mockMvc.perform(post("/v1/widget/chat")
                        .header("X-Site-Key", "invalid_key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyMessagesReturnsBadRequest() throws Exception {
        // site-key 는 유효 — 컨트롤러 레이어에서 400 반환 확인
        WidgetChatRequest request = new WidgetChatRequest(List.of());

        mockMvc.perform(post("/v1/widget/chat")
                        .header("X-Site-Key", "pk_test_widget_dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
