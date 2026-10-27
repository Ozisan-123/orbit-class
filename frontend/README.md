# Orbit 管理端

Orbit 智能教务中台的 Web 管理端，基于 React、TypeScript、Vite 和 Ant Design 构建。

## 功能页面

- 登录与首次密码修改
- 教师、班级、课程、教室、教学任务和用户管理
- 排课规则配置、任务发起、进度查看与不可行性诊断
- 班级课表、教师课表、课表版本、发布与 Excel 导出
- 空闲教师/教室查询、换课预览与确认
- 支持 SSE 流式响应的教务智能助手

## 开发

```bash
npm ci
npm run dev
```

开发服务器默认运行在 <http://localhost:5173>，`/api` 请求由 Vite 代理到 `http://127.0.0.1:8080`。可通过 `VITE_GATEWAY_URL` 修改网关地址。

## 检查与构建

```bash
npm run lint
npm run build
npm run preview
```
