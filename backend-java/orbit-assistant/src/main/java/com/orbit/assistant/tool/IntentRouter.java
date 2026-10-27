package com.orbit.assistant.tool;

import com.orbit.common.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 无模型时的确定性降级路由；不猜缺失参数，缺什么就明确追问。 */
@Component
public class IntentRouter {

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    public Routed route(String message, String explicitTool, Map<String, Object> explicitArguments) {
        if (explicitTool != null && !explicitTool.isBlank()) {
            return new Routed(explicitTool, explicitArguments == null ? Map.of() : explicitArguments);
        }
        String text = message.strip();
        Map<String, Object> args = new LinkedHashMap<>();
        if (text.contains("提议重排") || text.contains("重新排课")) {
            return new Routed("propose_reschedule", args);
        }
        if (text.contains("提议换课") || text.contains("换到")) {
            long version = requireAfter(text, "版本", "请提供版本 ID");
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("source_entry_id", requireAfter(text, text.contains("课程") ? "课程" : "记录", "请提供课程记录 ID"));
            request.put("target_class_id", requireAfter(text, "班级", "请提供目标班级 ID"));
            request.put("target_day_of_week", parseDay(text));
            request.put("target_period_index", parsePeriod(text));
            request.put("expected_revision", requireAfter(text, "修订", "请提供当前修订号"));
            return new Routed("propose_swap", Map.of("version_id", version, "request", request));
        }
        if (text.contains("提议规则") || text.contains("新增规则")) {
            return new Routed("propose_rule", Map.of("request", parseRule(text)));
        }
        if (text.contains("评分")) {
            args.put("version_id", requireAfter(text, "版本", "请说明要评分的版本 ID，例如“评分版本 12”"));
            return new Routed("score_timetable", args);
        }
        if (text.contains("诊断")) {
            Long version = optionalAfter(text, "版本");
            if (version != null) args.put("version_id", version);
            args.put("deep", text.contains("深度"));
            return new Routed("explain_diagnosis", args);
        }
        if (text.contains("配置") || text.contains("数据完整")) {
            return new Routed("check_config", args);
        }
        if (text.contains("任务")) {
            args.put("job_id", requireAfter(text, "任务", "请说明任务 ID，例如“查看任务 8”"));
            return new Routed("get_job_status", args);
        }
        if (text.contains("空闲") && text.contains("教师")) {
            args.put("teacher_id", requireAfter(text, "教师", "请提供教师 ID"));
            args.put("version_id", requireAfter(text, "版本", "请提供课表版本 ID"));
            args.put("day", parseDay(text));
            if (text.contains("全天") || text.contains("哪些节")) return new Routed("find_free_slots", args);
            args.put("period", parsePeriod(text));
            return new Routed("check_teacher_availability", args);
        }
        if (text.contains("空闲") && text.contains("班级")) {
            args.put("class_id", requireAfter(text, "班级", "请提供班级 ID"));
            args.put("version_id", requireAfter(text, "版本", "请提供课表版本 ID"));
            args.put("day", parseDay(text));
            args.put("period", parsePeriod(text));
            return new Routed("check_class_availability", args);
        }
        if (text.contains("课表")) {
            String type = text.contains("教师") ? "teacher" : text.contains("年级") ? "grade" : "class";
            args.put("resource_type", type);
            args.put("resource_id", requireAfter(text,
                    "teacher".equals(type) ? "教师" : "grade".equals(type) ? "年级" : "班级",
                    "请提供要查询的资源 ID"));
            Long version = optionalAfter(text, "版本");
            if (version != null) args.put("version_id", version);
            return new Routed("get_timetable", args);
        }
        throw BusinessException.badRequest("我还不能确定您要查询什么。可以问：评分版本 12、查看任务 8、深度诊断版本 12，或教师 3 在版本 12 周三第 2 节是否空闲。");
    }

    private static long parseDay(String text) {
        String[] names = {"周一", "周二", "周三", "周四", "周五"};
        for (int i = 0; i < names.length; i++) if (text.contains(names[i])) return i;
        throw BusinessException.badRequest("请说明星期，例如“周三”");
    }

    private static long parsePeriod(String text) {
        Matcher matcher = Pattern.compile("第\\s*(\\d+)\\s*节").matcher(text);
        if (!matcher.find()) throw BusinessException.badRequest("请说明节次，例如“第 2 节”");
        return Long.parseLong(matcher.group(1)) - 1;
    }

    private static Map<String, Object> parseRule(String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        String type;
        if (text.contains("不可用")) {
            type = "teacher_unavailable";
            params.put("teacher_id", requireAfter(text, "教师", "请提供教师 ID"));
            params.put("day", parseDay(text) + 1);
            params.put("period", parsePeriod(text) + 1);
        } else if (text.contains("空档")) {
            type = "teacher_minimize_gaps";
            Long teacher = optionalAfter(text, "教师");
            if (teacher != null) params.put("teacher_id", teacher);
        } else if (text.contains("不连排") || text.contains("不要连排")) {
            type = "subject_no_consecutive";
            Long subject = optionalAfter(text, "科目");
            if (subject != null) params.put("subject_id", subject);
        } else if (text.contains("最后一节") || text.contains("末节")) {
            type = "subject_avoid_last_period";
            Long subject = optionalAfter(text, "科目");
            if (subject != null) params.put("subject_id", subject);
        } else if (text.contains("上午")) {
            type = "main_subject_morning";
            Long subject = optionalAfter(text, "科目");
            if (subject != null) params.put("subject_id", subject);
            Long morning = optionalAfter(text, "前");
            params.put("morning_periods", morning == null ? 4 : morning);
        } else if (text.contains("教师") && (text.contains("一天最多") || text.contains("每天最多"))) {
            type = "teacher_max_daily_periods";
            Long teacher = optionalAfter(text, "教师");
            if (teacher != null) params.put("teacher_id", teacher);
            params.put("max", requireAfter(text, "最多", "请说明每天最多几节"));
        } else if (text.contains("一天最多") || text.contains("每天最多")) {
            type = "subject_max_per_day";
            Long subject = optionalAfter(text, "科目");
            if (subject != null) params.put("subject_id", subject);
            params.put("max", requireAfter(text, "最多", "请说明一天最多几节"));
        } else {
            throw BusinessException.badRequest("没有识别出规则类型。请说明：一天最多、不连排、教师不可用、末节、上午或空档。");
        }
        Long weight = optionalAfter(text, "权重");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rule_type", type);
        request.put("params", params);
        request.put("hardness", text.contains("硬规则") || text.contains("硬约束") ? "hard" : "soft");
        request.put("weight", weight == null ? 1 : weight);
        request.put("scope", "org");
        request.put("enabled", true);
        request.put("description", "助手草案：" + text);
        return request;
    }

    private static long requireAfter(String text, String marker, String error) {
        Long value = optionalAfter(text, marker);
        if (value == null) throw BusinessException.badRequest(error);
        return value;
    }

    private static Long optionalAfter(String text, String marker) {
        int index = text.indexOf(marker);
        if (index < 0) return null;
        Matcher matcher = NUMBER.matcher(text.substring(index + marker.length()));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    public record Routed(String tool, Map<String, Object> arguments) {
    }
}
