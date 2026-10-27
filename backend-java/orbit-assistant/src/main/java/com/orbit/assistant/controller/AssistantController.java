package com.orbit.assistant.controller;

import com.orbit.assistant.model.ActionResponse;
import com.orbit.assistant.model.ChatRequest;
import com.orbit.assistant.model.ConversationResponse;
import com.orbit.assistant.model.MessageResponse;
import com.orbit.assistant.model.UsageResponse;
import com.orbit.assistant.security.AssistantPrincipal;
import com.orbit.assistant.service.AssistantService;
import com.orbit.common.error.BusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request,
                           @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                           @AuthenticationPrincipal AssistantPrincipal principal) {
        SseEmitter emitter = new SseEmitter(120_000L);
        service.stream(requirePrincipal(principal), authorization, request, emitter);
        return emitter;
    }

    @GetMapping("/conversations")
    public List<ConversationResponse> conversations(@AuthenticationPrincipal AssistantPrincipal principal) {
        return service.conversations(requirePrincipal(principal));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageResponse> messages(@PathVariable long conversationId,
                                          @AuthenticationPrincipal AssistantPrincipal principal) {
        return service.messages(requirePrincipal(principal), conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> delete(@PathVariable long conversationId,
                                      @AuthenticationPrincipal AssistantPrincipal principal) {
        service.deleteConversation(requirePrincipal(principal), conversationId);
        return Map.of("ok", true);
    }

    @GetMapping("/actions")
    public List<ActionResponse> actions(
            @RequestParam(name = "conversation_id", required = false) Long conversationId,
            @AuthenticationPrincipal AssistantPrincipal principal) {
        return service.actions(requirePrincipal(principal), conversationId);
    }

    @PostMapping("/actions/{actionId}/confirm")
    public ActionResponse confirm(@PathVariable long actionId,
                                  @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                  @AuthenticationPrincipal AssistantPrincipal principal) {
        return service.confirm(requirePrincipal(principal), authorization, actionId);
    }

    @PostMapping("/actions/{actionId}/reject")
    public ActionResponse reject(@PathVariable long actionId,
                                 @AuthenticationPrincipal AssistantPrincipal principal) {
        return service.reject(requirePrincipal(principal), actionId);
    }

    @GetMapping("/usage")
    public UsageResponse usage(@AuthenticationPrincipal AssistantPrincipal principal) {
        return service.usage(requirePrincipal(principal));
    }

    private static AssistantPrincipal requirePrincipal(AssistantPrincipal principal) {
        if (principal == null) throw BusinessException.unauthorized("请先登录");
        return principal;
    }
}
