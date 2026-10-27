# Orbit 智能教务中台

Orbit 是面向学校教务场景的自动排课系统。后端采用 Java 21 与 Spring Boot 多模块架构，使用 OR-Tools CP-SAT 处理教师、班级、课时、固定时段和课程分布等约束；前端采用 React、TypeScript 与 Ant Design，提供教务数据维护、规则配置、排课、课表查看、换课和智能助手等功能。

## 核心能力

- 教师、班级、课程、教室、年级和教学任务等基础数据管理
- 可配置硬约束与软约束，支持规则校验、影响试算和不可行性诊断
- 异步排课任务、租约心跳、`job_token` 栅栏和结果幂等落库
- 课表版本管理、发布、查询、Excel 导出、空闲资源查询和换课预览
- 智能助手通过白名单工具查询教务数据，并对写操作执行“提议—确认—执行”流程

## 技术栈

- 后端：Java 21、Spring Boot、Spring Cloud Gateway、Spring Security、MyBatis-Plus、Flyway
- 求解：Google OR-Tools CP-SAT
- 数据：MySQL 8.4、Redis 7
- 前端：React 19、TypeScript、Vite、Ant Design、Zustand、Axios
- 测试：JUnit 5、Testcontainers、前端 Oxlint 与 TypeScript 构建检查

## 目录结构

```text
orbit/
├─ backend-java/           Java 多模块后端与本地基础设施
│  ├─ orbit-academic/      认证、组织与教务基础数据
│  ├─ orbit-scheduling/    排课编排、规则、任务、版本与换课
│  ├─ orbit-solver-core/   CP-SAT 求解内核和规则实现
│  ├─ orbit-solver-worker/ 独立求解进程
│  ├─ orbit-assistant/     智能助手与工具调用
│  ├─ orbit-gateway/       统一 API 网关
│  └─ orbit-common/        公共 DTO、异常与租户上下文
├─ frontend/               React 管理端
├─ docs/                   架构、方案、代码导读与验证报告
└─ start-dev.cmd           Windows 一键启动脚本
```

## 本地启动

前置环境：JDK 21、Maven 3.9+、Node.js 20+、Docker Desktop。

```powershell
Copy-Item .env.example .env
.\start-dev.cmd
```

启动完成后访问 <http://localhost:5173>。开发环境默认管理员账号是 `admin`，密码由 `.env` 中的 `ORBIT_ADMIN_PASSWORD` 设置；示例配置为 `Admin@123`，首次登录后请立即修改。

如需启用大模型能力，请在本地 `.env` 中填写 `LLM_API_KEY`，并按需修改 `LLM_BASE_URL` 与 `LLM_MODEL`。未配置密钥时，助手仍可使用本地意图路由和白名单工具能力。

## 分开构建

```powershell
# Java 后端
Set-Location backend-java
mvn clean test

# React 前端
Set-Location ..\frontend
npm ci
npm run lint
npm run build
```

后端模块关系、服务端口和设计原则见 [Java 后端说明](backend-java/README.md)，完整实现导读见 [项目代码导读](docs/代码导读.md)。

## 验证基线

- Java 后端全量自动化测试：87 项通过
- 前端静态检查与生产构建通过
- 同输入、同规则、2 个求解线程、12 秒求解时限下，196 班规模连续 3/3 次生成 5,880 节有效课表

详细实验条件和结果见 [Java 压力测试与优化实验](docs/Java压力测试与优化实验-2026-10-26.md)。

## 安全说明

- `.env`、证书、数据库文件、运行日志和构建产物均不会提交到仓库。
- `.env.example` 仅用于本地开发，请勿将示例口令用于生产环境。
- 生产部署必须替换数据库口令、JWT 密钥、Worker 共享密钥和管理员初始密码。
