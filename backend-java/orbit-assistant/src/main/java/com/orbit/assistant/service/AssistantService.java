package com.orbit.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.assistant.model.ActionResponse;
import com.orbit.assistant.model.ChatRequest;
import com.orbit.assistant.model.ConversationResponse;
import com.orbit.assistant.model.MessageResponse;
import com.orbit.assistant.model.UsageResponse;
import com.orbit.assistant.repository.AssistantRepository;
import com.orbit.assistant.security.AssistantPrincipal;
import com.orbit.assistant.tool.IntentRouter;
import com.orbit.assistant.tool.ToolGateway;
import com.orbit.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class AssistantService {
    private static final Logger LOG = LoggerFactory.getLogger(AssistantService.class);

    private final AssistantRepository repository;
    private final IntentRouter router;
    private final LlmIntentRouter llmRouter;
    private final ToolGateway tools;
    private final ObjectMapper json;
    private final long dailyBudget;
    private final int maxStreams;
    private final ConcurrentHashMap<Long, Semaphore> streams = new ConcurrentHashMap<>();

    public AssistantService(AssistantRepository repository, IntentRouter router,
                            LlmIntentRouter llmRouter,
                            ToolGateway tools, ObjectMapper json,
                            @Value("${orbit.assistant.daily-token-budget-per-org:200000}") long dailyBudget,
                            @Value("${orbit.assistant.max-concurrent-streams-per-org:2}") int maxStreams) {
        this.repository = repository;
        this.router = router;
        this.llmRouter = llmRouter;
        this.tools = tools;
        this.json = json;
        this.dailyBudget = dailyBudget;
        this.maxStreams = maxStreams;
    }

    public void stream(AssistantPrincipal principal, String authorization,
                       ChatRequest request, SseEmitter emitter) {
        Semaphore semaphore = streams.computeIfAbsent(principal.organizationId(),
                ignored -> new Semaphore(maxStreams));
        if (!semaphore.tryAcquire()) {
            sendError(emitter, "当前学校已有过多助手对话，请稍后再试");
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                runTurn(principal, authorization, request, emitter);
            } finally {
                semaphore.release();
            }
        });
    }

    private void runTurn(AssistantPrincipal principal, String authorization,
                         ChatRequest request, SseEmitter emitter) {
        ConversationResponse conversation = request.conversationId() == null
                ? repository.createConversation(principal.organizationId(), principal.userId(), title(request.message()))
                : repository.requireConversation(principal.organizationId(), principal.userId(), request.conversationId());
        int turn = repository.nextTurn(conversation.id());
        repository.addMessage(conversation.id(), principal.organizationId(), principal.userId(), turn,
                "user", request.message(), tokens(request.message()));
        send(emitter, "meta", Map.of("conversation_id", conversation.id(), "turn_index", turn));

        try {
            IntentRouter.Routed routed = route(request, principal.organizationId());
            boolean proposal = ToolGateway.PROPOSAL_TOOLS.contains(routed.tool());
            if (!proposal && !ToolGateway.READ_TOOLS.contains(routed.tool())) {
                throw BusinessException.badRequest("该工具不在助手允许清单中");
            }
            long used = repository.usedTokensToday(principal.organizationId());
            if (proposal && used >= dailyBudget) {
                throw BusinessException.tooManyRequests("今日助手额度已用完，目前只允许只读查询");
            }

            send(emitter, "tool_call", Map.of("tool", routed.tool(), "arguments", routed.arguments()));
            String answer;
            if (proposal) {
                ToolGateway.Proposal prepared = tools.prepareProposal(routed.tool(), routed.arguments(), authorization);
                long actionId = repository.addAction(principal.organizationId(), principal.userId(),
                        conversation.id(), turn, routed.tool(), routed.arguments(), null,
                        prepared.body(), "proposed", prepared.expectedRevision());
                if ("propose_rule".equals(routed.tool())) {
                    repository.addRuleAttempt(principal.organizationId(), principal.userId(), actionId,
                            request.message(), prepared.body());
                }
                send(emitter, "proposal", Map.of("action_id", actionId, "tool", routed.tool(),
                        "proposal", prepared.body(), "status", "proposed"));
                answer = "已生成草案并完成预检查。草案不会自动执行，请核对差异后选择确认或取消。";
            } else {
                Map<String, Object> result = tools.executeRead(routed.tool(), routed.arguments(), authorization);
                long actionId = repository.addAction(principal.organizationId(), principal.userId(),
                        conversation.id(), turn, routed.tool(), routed.arguments(), result,
                        null, "completed", null);
                send(emitter, "tool_result", Map.of("action_id", actionId, "tool", routed.tool(), "result", result));
                answer = readable(routed.tool(), result);
            }
            repository.addMessage(conversation.id(), principal.organizationId(), principal.userId(), turn,
                    "assistant", answer, tokens(answer));
            streamText(emitter, answer);
            send(emitter, "done", Map.of("conversation_id", conversation.id(), "turn_index", turn));
            emitter.complete();
        } catch (LlmIntentRouter.ClarificationRequiredException error) {
            String answer = error.getMessage();
            repository.addMessage(conversation.id(), principal.organizationId(), principal.userId(), turn,
                    "assistant", answer, tokens(answer));
            streamText(emitter, answer);
            send(emitter, "done", Map.of("conversation_id", conversation.id(), "turn_index", turn));
            emitter.complete();
        } catch (BusinessException error) {
            String answer = error.detail();
            repository.addMessage(conversation.id(), principal.organizationId(), principal.userId(), turn,
                    "assistant", answer, tokens(answer));
            sendError(emitter, answer);
        } catch (Exception error) {
            LOG.error("助手回合执行失败 conversation={} turn={}", conversation.id(), turn, error);
            sendError(emitter, "助手暂时不可用，请稍后重试");
        }
    }

    public List<ConversationResponse> conversations(AssistantPrincipal principal) {
        return repository.conversations(principal.organizationId(), principal.userId());
    }

    public List<MessageResponse> messages(AssistantPrincipal principal, long conversationId) {
        return repository.messages(principal.organizationId(), principal.userId(), conversationId);
    }

    public void deleteConversation(AssistantPrincipal principal, long conversationId) {
        repository.deleteConversation(principal.organizationId(), principal.userId(), conversationId);
    }

    public List<ActionResponse> actions(AssistantPrincipal principal, Long conversationId) {
        return repository.actions(principal.organizationId(), principal.userId(), conversationId);
    }

    public UsageResponse usage(AssistantPrincipal principal) {
        long used = repository.usedTokensToday(principal.organizationId());
        return new UsageResponse(used, dailyBudget, Math.max(0, dailyBudget - used),
                llmRouter.enabled(), llmRouter.enabled() ? "llm" : "local");
    }

    private IntentRouter.Routed route(ChatRequest request, long organizationId) {
        if (request.tool() != null && !request.tool().isBlank()) {
            return router.route(request.message(), request.tool(), request.arguments());
        }
        if (llmRouter.enabled() && repository.usedTokensToday(organizationId) < dailyBudget) {
            try {
                return llmRouter.route(request.message());
            } catch (LlmIntentRouter.ClarificationRequiredException error) {
                throw error;
            } catch (RuntimeException ignored) {
                // 模型服务不可达时自动退回确定性本地路由，保证核心查询可用。
            }
        }
        return router.route(request.message(), null, request.arguments());
    }

    @Transactional
    public ActionResponse confirm(AssistantPrincipal principal, String authorization, long actionId) {
        ActionResponse action = repository.requireActionForUpdate(
                principal.organizationId(), principal.userId(), actionId);
        if (!"proposed".equals(action.status())) {
            throw BusinessException.conflict("该草案已经处理，当前状态：" + action.status());
        }
        try {
            Map<String, Object> result = tools.apply(action.toolName(), action.proposal(), authorization);
            Integer revision = extractRevision(result);
            repository.decideAction(action.id(), "confirmed", result, revision, null);
        } catch (BusinessException error) {
            repository.decideAction(action.id(), "failed", null, null, error.detail());
        }
        return repository.requireAction(principal.organizationId(), principal.userId(), actionId);
    }

    @Transactional
    public ActionResponse reject(AssistantPrincipal principal, long actionId) {
        ActionResponse action = repository.requireActionForUpdate(
                principal.organizationId(), principal.userId(), actionId);
        if (!"proposed".equals(action.status())) {
            throw BusinessException.conflict("该草案已经处理，当前状态：" + action.status());
        }
        repository.decideAction(action.id(), "rejected", action.result(), null, null);
        return repository.requireAction(principal.organizationId(), principal.userId(), actionId);
    }

    @SuppressWarnings("unchecked")
    private static Integer extractRevision(Map<String, Object> result) {
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map && map.get("revision") instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String readable(String tool, Map<String, Object> result) {
        try {
            return "已调用 “" + tool + "” 完成查询。以下内容全部来自业务系统：\n"
                    + json.writerWithDefaultPrettyPrinter().writeValueAsString(result.get("data"));
        } catch (JsonProcessingException error) {
            return "查询已完成，请展开工具轨迹查看结果。";
        }
    }

    private static int tokens(String text) {
        return Math.max(1, (text == null ? 0 : text.length() + 3) / 4);
    }

    private static String title(String message) {
        String clean = message.strip().replaceAll("\\s+", " ");
        return clean.length() <= 30 ? clean : clean.substring(0, 30) + "…";
    }

    private static void streamText(SseEmitter emitter, String answer) {
        for (int start = 0; start < answer.length(); start += 24) {
            send(emitter, "token", Map.of("text", answer.substring(start, Math.min(answer.length(), start + 24))));
        }
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException error) {
            throw new IllegalStateException("SSE 连接已断开", error);
        }
    }

    private static void sendError(SseEmitter emitter, String detail) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("detail", detail)));
            emitter.complete();
        } catch (IOException error) {
            emitter.completeWithError(error);
        }
    }
}
