package com.orbit.assistant.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.assistant.model.ActionResponse;
import com.orbit.assistant.model.ConversationResponse;
import com.orbit.assistant.model.MessageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class AssistantRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AssistantRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public ConversationResponse createConversation(long orgId, long userId, String title) {
        LocalDateTime now = UtcTimes.now();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO assistant_conversations
                    (organization_id,user_id,title,created_at,updated_at) VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orgId);
            statement.setLong(2, userId);
            statement.setString(3, title);
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            return statement;
        }, key);
        return new ConversationResponse(key.getKey().longValue(), title, now, now);
    }

    public ConversationResponse requireConversation(long orgId, long userId, long id) {
        List<ConversationResponse> rows = jdbc.query("""
                SELECT id,title,created_at,updated_at FROM assistant_conversations
                WHERE id=? AND organization_id=? AND user_id=?
                """, (rs, row) -> new ConversationResponse(rs.getLong("id"), rs.getString("title"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()), id, orgId, userId);
        if (rows.isEmpty()) throw BusinessException.notFound("助手会话不存在");
        return rows.getFirst();
    }

    public List<ConversationResponse> conversations(long orgId, long userId) {
        return jdbc.query("""
                SELECT id,title,created_at,updated_at FROM assistant_conversations
                WHERE organization_id=? AND user_id=? ORDER BY updated_at DESC LIMIT 100
                """, (rs, row) -> new ConversationResponse(rs.getLong("id"), rs.getString("title"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()), orgId, userId);
    }

    public void deleteConversation(long orgId, long userId, long id) {
        requireConversation(orgId, userId, id);
        jdbc.update("DELETE FROM assistant_conversations WHERE id=?", id);
    }

    public int nextTurn(long conversationId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(turn_index),0)+1 FROM assistant_messages WHERE conversation_id=?",
                Integer.class, conversationId);
        return value == null ? 1 : value;
    }

    public MessageResponse addMessage(long conversationId, long orgId, long userId, int turn,
                                      String role, String content, int tokens) {
        LocalDateTime now = UtcTimes.now();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO assistant_messages
                    (conversation_id,organization_id,user_id,turn_index,role,content,token_count,created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, conversationId);
            statement.setLong(2, orgId);
            statement.setLong(3, userId);
            statement.setInt(4, turn);
            statement.setString(5, role);
            statement.setString(6, content);
            statement.setInt(7, tokens);
            statement.setTimestamp(8, Timestamp.valueOf(now));
            return statement;
        }, key);
        jdbc.update("UPDATE assistant_conversations SET updated_at=? WHERE id=?",
                Timestamp.valueOf(now), conversationId);
        return new MessageResponse(key.getKey().longValue(), conversationId, turn, role, content, tokens, now);
    }

    public List<MessageResponse> messages(long orgId, long userId, long conversationId) {
        requireConversation(orgId, userId, conversationId);
        return jdbc.query("""
                SELECT id,conversation_id,turn_index,role,content,token_count,created_at
                FROM assistant_messages WHERE conversation_id=? ORDER BY id
                """, (rs, row) -> new MessageResponse(rs.getLong("id"), rs.getLong("conversation_id"),
                rs.getInt("turn_index"), rs.getString("role"), rs.getString("content"),
                rs.getInt("token_count"), rs.getTimestamp("created_at").toLocalDateTime()), conversationId);
    }

    public long addAction(long orgId, long userId, long conversationId, int turn, String tool,
                          Map<String, Object> arguments, Map<String, Object> result,
                          Map<String, Object> proposal, String status, Integer expectedRevision) {
        LocalDateTime now = UtcTimes.now();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO assistant_actions
                    (organization_id,user_id,conversation_id,turn_index,tool_name,arguments_json,
                     result_json,proposal_json,status,expected_revision,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orgId);
            statement.setLong(2, userId);
            statement.setLong(3, conversationId);
            statement.setInt(4, turn);
            statement.setString(5, tool);
            statement.setString(6, write(arguments));
            statement.setString(7, result == null ? null : write(result));
            statement.setString(8, proposal == null ? null : write(proposal));
            statement.setString(9, status);
            if (expectedRevision == null) statement.setNull(10, java.sql.Types.INTEGER);
            else statement.setInt(10, expectedRevision);
            statement.setTimestamp(11, Timestamp.valueOf(now));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public ActionResponse requireAction(long orgId, long userId, long id) {
        return findAction(orgId, userId, id, false);
    }

    public ActionResponse requireActionForUpdate(long orgId, long userId, long id) {
        return findAction(orgId, userId, id, true);
    }

    private ActionResponse findAction(long orgId, long userId, long id, boolean lock) {
        List<ActionResponse> rows = jdbc.query("""
                SELECT * FROM assistant_actions WHERE id=? AND organization_id=? AND user_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> action(rs), id, orgId, userId);
        if (rows.isEmpty()) throw BusinessException.notFound("助手草案不存在");
        return rows.getFirst();
    }

    public List<ActionResponse> actions(long orgId, long userId, Long conversationId) {
        String sql = "SELECT * FROM assistant_actions WHERE organization_id=? AND user_id=?"
                + (conversationId == null ? "" : " AND conversation_id=?") + " ORDER BY id DESC LIMIT 200";
        Object[] args = conversationId == null ? new Object[] {orgId, userId}
                : new Object[] {orgId, userId, conversationId};
        return jdbc.query(sql, (rs, row) -> action(rs), args);
    }

    public void decideAction(long id, String status, Map<String, Object> result,
                             Integer finalRevision, String error) {
        jdbc.update("""
                UPDATE assistant_actions SET status=?,result_json=?,final_revision=?,error_detail=?,decided_at=?
                WHERE id=?
                """, status, result == null ? null : write(result), finalRevision, error,
                Timestamp.valueOf(UtcTimes.now()), id);
        if ("confirmed".equals(status) || "rejected".equals(status)) {
            jdbc.update("UPDATE rule_generation_attempts SET accepted=?,decided_at=? WHERE action_id=?",
                    "confirmed".equals(status), Timestamp.valueOf(UtcTimes.now()), id);
        }
    }

    public void addRuleAttempt(long orgId, long userId, long actionId, String text,
                               Map<String, Object> generatedRule) {
        jdbc.update("""
                INSERT INTO rule_generation_attempts
                (organization_id,user_id,action_id,original_text,generated_rule,created_at)
                VALUES (?,?,?,?,?,?)
                """, orgId, userId, actionId, text, write(generatedRule),
                Timestamp.valueOf(UtcTimes.now()));
    }

    public long usedTokensToday(long orgId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(token_count),0) FROM assistant_messages
                WHERE organization_id=? AND created_at >= UTC_DATE()
                """, Long.class, orgId);
        return value == null ? 0 : value;
    }

    private ActionResponse action(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new ActionResponse(rs.getLong("id"), rs.getLong("conversation_id"),
                rs.getInt("turn_index"), rs.getString("tool_name"),
                read(rs.getString("arguments_json")), read(rs.getString("result_json")),
                read(rs.getString("proposal_json")), rs.getString("status"),
                (Integer) rs.getObject("expected_revision"), (Integer) rs.getObject("final_revision"),
                rs.getString("error_detail"), rs.getTimestamp("created_at").toLocalDateTime(),
                decided == null ? null : decided.toLocalDateTime());
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("助手参数无法序列化", error);
        }
    }

    private Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readValue(value, MAP);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("助手审计 JSON 已损坏", error);
        }
    }
}
