# 代码导读 · 4e. 鉴权与配置

> **本册范围**：`orbit-scheduling` 的 `security/`（4 个类）+ `config/`（2 个）+ 启动类，共 **7 个类**：
> `AuthUser`、`AuthUserMapper`、`SchedulePrincipal`、`JwtAuthenticationFilter`、
> `SchedulingSecurityConfig`、`SchedulingInfraConfig`、`SchedulingApplication`。
>
> 上游：[代码导读.md](代码导读.md)（第 1 册里 `JwtService` / `AuthFailure` 是 `orbit-common` 的，与本册直接相关）
>
> ⚠ 补写册：这一块原在旧版 `4a` 里，被覆盖时丢了。

---

## 本模块在系统里的位置

**这个服务不签 token，只认 token** ✓

```
POST /api/auth/login  →  academic（:8081）
                          唯一签发 token 的地方（用 APP_SECRET_KEY 签 HS256）
                          ↓ 用户拿到 token，存进 localStorage
任意请求  Authorization: Bearer <token>
        ↓
   Vite 按路径分流
        ├─ /api/schedule/** → scheduling（:8083）
        └─ 其余            → academic （:8081，这里才有登录接口与 PasswordEncoder）
```

**两个服务用同一个密钥**（`APP_SECRET_KEY`）✓ —— **一个签发、两个都认** ✓
所以 scheduling 里**没有** `PasswordEncoder`、没有 `AuthenticationManager`、没有 `/api/auth/login` ✓
它只是"读一下 token 是谁、属于哪个组织、什么角色" ✓ 然后放行或 401 ✓

**这一册的类分三块**：

| 块 | 类 | 干什么 |
|---|---|---|
| **认人** | `AuthUser` / `AuthUserMapper` / `SchedulePrincipal` / `JwtAuthenticationFilter` | 把 token 变成安全上下文 |
| **定规矩** | `SchedulingSecurityConfig` | 哪些路径放行、未认证时回什么 |
| **装零件** | `SchedulingInfraConfig` / `SchedulingApplication` | JWT bean、分页拦截器、扫描范围 |

## 阅读顺序建议

1. **`JwtAuthenticationFilter.doFilterInternal`** —— 整册的主线就是它那 15 行。
2. **`SchedulingSecurityConfig` 的入口点** —— 它和过滤器是**配对**的（一个写原因、一个选文案），分开读会看不懂为什么要有请求属性。
3. 其余四个类扫一眼即可（都是装配或纯字段）。

---

## 4e.1 `AuthUser` —— **只读**的 `users` 映射，且**刻意不含口令哈希**

```java
@TableName("users")
public class AuthUser {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private String username;
    private String displayName;
    private String role;
    private Boolean isActive;      // ← 列名是 is_active，字段名 isActive
}
```

**这张表属于 academic** ✓ 那 scheduling 读它算不算违反铁律 2 ✗ —— **不算** ✓
类注释把这件事讲得很清楚：

> ⚠ 铁律 2 说的是「禁止跨服务**写**库」。鉴权必须知道"这个 token 对应谁、属于哪个组织、是什么角色"，读是唯一可行的方式。

并且**同时承认了这是一处耦合** ✓：

> ⚠ 但这确实是**一处耦合**，拆成独立库时会断：届时要么把鉴权放到网关（校验 token 后把 userId/organizationId/role 放进请求头），要么各服务维护一份只读的用户投影。

> 💡 **这是"承认技术债"该有的写法**：先说明"为什么现在这样做是对的" ✓
> 再说明"什么时候它会变成问题、届时怎么办" ✓
> 而不是只写前一半（那会让人以为可以永远这样）✗ 也不是只写后一半（那会让人以为现在就错了）✗

### 🔴 只映射鉴权需要的列 —— 这**不是**省事，是安全设计

类注释：

> 只映射鉴权需要的列 —— 刻意**不映射 `password_hash`**：
> scheduling 从不校验口令（那是 academic 的登录接口的事），
> **不映射就没有泄露风险，也避免别人误用**。

**这条值得单独想一下** ✗ —— 一般人的直觉是"实体就该对应整张表" ✓ 多映射几个列有什么关系 ✗

**关系在这里**：

| 如果映射了 `password_hash` | 现在 |
|---|---|
| 它会出现在所有 `selectList` 的结果里 ✓ 躺在内存的对象里 ✓ 可能被日志打出来 ✓ 可能被调试器看到 ✓ 更糟的是：**将来某个接口顺手把它序列化进响应** ✗ | 它**根本不存在于这个服务的类型系统里** ✗ 想泄露也没有东西可泄露 ✓ |

**这是"用类型系统做安全边界"** ✓ —— 比任何"记得不要返回它"的约定都可靠 ✓
因为**约定会被人忘记，而编译不过的代码不会** ✓

> 对照 `security/AuthUser` 与 `snapshot/ClassRef` —— 两个类的注释**互相引用**（"与 `security.AuthUser` 同理"）
> ✓ 说明这是一个**有意的、成体系的**做法 ✓ 不是某一处的随意发挥 ✓

### `active()` —— 一个"语义化"的小方法

```java
private Boolean isActive;                     // 包装类型

public boolean active() {
    return Boolean.TRUE.equals(isActive);
}
```

**两个细节**：

1. **字段是 `Boolean` 不是 `boolean`** ✓ —— 表列可能为 `null` ✓ 用原始类型在 MyBatis 赋值时可能抛错 ✓（同 `TeacherRef.maxWeeklyHours` ✓）
2. **`active()` 而不是 `getIsActive()`** ✓ —— 前者是**业务语义**（"这个账号可用吗"）✓ 后者是**字段访问**（"这个标志位的值是什么"）✓
   区别在于：`getIsActive()` 返回 `Boolean` ✗ 调用方还得自己判 `null` ✓ 而 `active()` 已经把 `null` 处理成 `false` ✓ **把"null 该怎么办"这个决定收在了数据旁边** ✓

> ⚠ 顺带一个 MyBatis-Plus 的命名细节：Java 字段 `isActive` 对应的列是 `is_active` ✓
> 而 `Boolean isActive` 的 getter 若写成 `isActive()` ✓ MyBatis-Plus 的默认命名策略能推出 `is_active` ✓
> 这里**额外写了一个 `getIsActive()`** ✓ 所以不会有歧义 ✓

---

## 4e.2 `AuthUserMapper` —— 9 行，一个字都不多

```java
@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUser> { }
```

**它是空的** ✓ 因为只需要 `selectOne` / `selectById` ✓ 都是 `BaseMapper` 自带的 ✓

> 和 4b-2 册里那 7 个 `*Ref` Mapper 一样 —— **只读场景不需要自定义 SQL** ✓
> 「看一个 Mapper 有没有自定义 SQL，能猜出它服务的是读还是并发写」这个判断法，这里再次成立 ✓

---

## 4e.3 `SchedulePrincipal` —— 朴素 record，**不是** `UserDetails`

```java
public record SchedulePrincipal(Long userId, Long organizationId, String username,
                                String displayName, String role) {

    /** 对应 Python 的 require_admin 判定（admin 与 platform_admin）。 */
    public boolean isAdmin() {
        return "admin".equals(role) || "platform_admin".equals(role);
    }
}
```

**为什么不实现 Spring Security 的 `UserDetails`** ✗ 类注释给了两条理由：

1. **scheduling 在 Maven 层面不依赖 academic** ✓ 拆进程后要独立部署 ✓ 所以不能复用 academic 的 `OrbitUserPrincipal` ✓（"两份相似的类是刻意的" ✓）
2. **scheduling 不需要 `UserDetails`** ✓ —— 那是"口令认证"那一套（`getPassword()` / `isAccountNonExpired()` …）✓ 而 scheduling 从不校验口令 ✓ 实现它就要写一堆永远返回 `true` 的方法 ✗ 纯粹是噪音 ✓

**而且技术上根本不需要** ✓ 注释最后一句点出了关键：
> `UsernamePasswordAuthenticationToken` 的 principal **可以是任意对象** ✓

> 💡 **这是一个常见的过度设计**：看到 Spring Security 就以为自定义 principal 必须实现 `UserDetails` ✗
> 实际上 `UserDetails` 只在**认证流程**里被用到 ✓ 而这里的认证流程是"过滤器自己校验 JWT" ✓
> 根本走不到 `UserDetailsService` ✓ 所以 principal 是什么类型**完全自由** ✓

### `isAdmin()` —— 与 Python 的 `require_admin` 对齐

`admin` 与 `platform_admin` **都算管理员** ✓ 这是 Python 的语义 ✓ 注释标明了出处 ✓

⚠ 注意它与 `SchedulingSecurityConfig` 里那个 403 文案的关系（见 §4e.5）：
**具体的权限判断写在各个 Controller 里**（调 `principal.isAdmin()` ✓）✓
Spring Security 那一层的 403 只是**安全网** ✓

---

## 4e.4 `JwtAuthenticationFilter` —— 整册的主线

**继承 `OncePerRequestFilter`** ✓ —— 这个基类保证"每个请求只跑一次" ✓
（过滤器可能因为 forward/include/error 派发被调用多次 ✗ 不继承就得自己防重 ✓）

### `doFilterInternal` —— 15 行的三层判断

```java
protected void doFilterInternal(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain) throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {       // ①
        String token = bearerToken(request);
        if (token == null) {
            request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.NO_TOKEN);   // ②
        } else {
            Optional<Long> userId = jwtService.userIdOf(token);
            if (userId.isEmpty()) {
                request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.INVALID_TOKEN);  // ③
            } else {
                authenticate(request, userId.get());                              // ④
            }
        }
    }
    filterChain.doFilter(request, response);                                     // ⑤
}
```

| # | 做什么 | 为什么 / 注意 |
|---|---|---|
| **①** | **先看上下文里有没有认证** | ⚠ 不能省 ✗ 如果前面的过滤器（或本过滤器在别的派发阶段）已经设好了认证 ✓ 这里再走一遍会**重复查库** ✓ 更糟的是可能**覆盖**已有认证 ✓ 所以是"没有才做" ✓ |
| **②** | 没 token → 记 `NO_TOKEN` | 对应 Python 的 `请先登录` ✓ |
| **③** | token 有但校验不过 → 记 `INVALID_TOKEN` | 对应 `登录已失效，请重新登录` ✓ 覆盖：签名不对 / 过期 / 格式非法 / subject 不是数字 ✓（`JwtService.userIdOf` 统一返回 `Optional.empty()` ✓） |
| **④** | token 有效 → 查库装载用户 | 见下 `authenticate` ✓ |
| **⑤** | **无论如何都放行到下一个过滤器** | 🔴 **这是关键**：过滤器**不做"拒绝"这个动作** ✗ 它只**记录原因** ✓ 真正拒绝发生在授权层（`anyRequest().authenticated()` ✓）→ 未认证 → 交给 `AuthenticationEntryPoint` ✓ |

> 💡 **为什么非要这样拆**（记录的人 vs 拒绝的人）✗
> 因为**放行与否是"授权"的决定，不是"认证"的决定** ✓
> 有些路径（`/api/health`、`/internal/**`）**不需要认证** ✓
> 如果过滤器在这里就 `response.setStatus(401)` 并 return ✗ 那些路径**永远进不去** ✗
> 所以过滤器必须"只记不说" ✓ 把判断权交给后面的授权规则 ✓
> 这也正是 `AuthFailure` 那个请求属性存在的原因 —— **跨过过滤器与入口点之间的职责边界** ✓

### `authenticate(request, userId)` —— 查库、装上下文

```java
private void authenticate(HttpServletRequest request, long userId) {
    AuthUser user = authUserMapper.selectOne(Wrappers.<AuthUser>lambdaQuery()
            .eq(AuthUser::getId, userId));
    if (user == null || !user.active()) {
        // token 合法但账号不可用 —— 与 Python 的 filter(is_active.is_(True)) 一致
        request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.UNKNOWN_USER);
        SecurityContextHolder.clearContext();
        return;
    }
    SchedulePrincipal principal = new SchedulePrincipal(user.getId(), user.getOrganizationId(),
            user.getUsername(), user.getDisplayName(), user.getRole());
    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + String.valueOf(user.getRole()).toUpperCase())));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
}
```

逐点：

| 代码 | 说明 |
|---|---|
| `selectOne(lambdaQuery().eq(AuthUser::getId, userId))` | **每次请求查一次库** ✓ 这是无状态的代价 ✓（也意味着**用户被停用后立即失效** ✓ 不需要等 token 过期 ✓ —— 这是查库换来的好处 ✓） |
| `user == null \|\| !user.active()` | 只一条判断覆盖**两种**情况：用户不存在 / 用户被停用 ✓ 而且**两处都是同一句文案** ✓ 因为 Python 那边就是 `is_active.is_(True)` 一个条件 ✓ 查不到就 401 ✓ **不需要区分**（区分反而会泄露"这个用户存在但被停用了" ✓） |
| ⚠ **`SecurityContextHolder.clearContext()`** | 注释没写为什么 ✓ 但**必须调** ✗ 场景：前面的派发阶段已经设了认证 ✓ 现在发现账号已被停用 ✓ 不清的话**旧认证还在上下文里** ✗ → 这个请求**照样能通过授权** ✗ 这是一个很隐蔽的安全洞 ✓ **放在这里是对的，但值得补一句注释** ✓ |
| `new UsernamePasswordAuthenticationToken(principal, null, authorities)` | 三参构造器 = **"已认证"** ✓（两参构造器才是"待认证" ✓ 用错了会让 `authenticated()` 返回 false ✗）<br>`null` 是 credentials（口令）✓ —— **认证已经完成了，不再需要凭证** ✓ |
| `"ROLE_" + ...toUpperCase()` | Spring Security 的约定：`hasRole("ADMIN")` 实际匹配的是权限 `ROLE_ADMIN` ✓ 所以前缀不能省 ✓<br>`String.valueOf(...)` 防 `role` 为 `null` ✓（`null` → `"null"` ✗ 语义上不好听 ✓ 但不会 NPE ✓） |
| `setDetails(new WebAuthenticationDetailsSource().buildDetails(request))` | 把请求的远端地址/会话 id 挂到认证对象上 ✓ 给审计日志用 ✓ 现在没人在读它 ✓ 但这是标准做法 ✓ |
| `SecurityContextHolder.getContext().setAuthentication(...)` | 装进上下文 ✓ 之后 Controller 里 `@AuthenticationPrincipal SchedulePrincipal` 就能取到 ✓ |

> ⚠ **一处值得与 Python 核对的细节**：`toUpperCase()` **没有指定 `Locale`** ✗
> 在土耳其语环境下 `"i".toUpperCase()` 会得到 `"İ"` 而不是 `"I"` ✓ 这是 Java 的经典陷阱 ✓
> 本项目里 `role` 的取值是 `admin` / `platform_admin` / `operator` ✓ **都不含 `i`** ✓ 所以现在无害 ✓
> 但如果将来加一个 `"inspector"` 角色 ✗ 在土耳其服务器上就会得到 `ROLE_İNSPECTOR` ✗ 权限判断静默失效 ✓
> **规范做法是 `toUpperCase(Locale.ROOT)`** ✓ —— 我没有改动代码 ✓ 记在这里备查 ✓

### `bearerToken(request)` —— 取 token

```java
private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
        return null;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
}
```

| 代码 | 说明 |
|---|---|
| `regionMatches(true, 0, "Bearer ", 0, 7)` | ⚠ **`true` = 忽略大小写** ✓ 所以 `bearer xxx` / `BEARER xxx` 都接受 ✓<br>**为什么不用 `startsWith`**：`startsWith` 区分大小写 ✗ 那样 `bearer` 会被当成"没带 token" → 401 `请先登录` ✗ 而用户明明带了 ✓ 排查时很困惑 ✓ |
| `.trim()` | 容忍 `Bearer  xxx`（多空格）✓ |
| `token.isEmpty() ? null : token` | ⚠ **关键**：`"Bearer "`（后面什么都没有）必须当成**没带 token** ✓ 而不是"带了一个空 token" ✗ 后者会让 `userIdOf("")` 返回 `empty()` → 文案变成 `登录已失效，请重新登录` ✗ **而正确文案是 `请先登录`** ✓ 这两种情况用户要做的事完全不同（重新登录 vs 先登录）✓ |

> ✅ **两处"看起来可能分叉"的地方，已核对 Python 源码，结论是「一致」** ✓
>
> Python 用的是 FastAPI 内置的 `HTTPBearer` ✓ 而它的解析在 `fastapi/security/utils.py`：
> ```python
> scheme, _, param = authorization_header_value.partition(" ")
> return scheme, param.strip()          # ← 它也 strip()
> # 而 scheme 的比较是 scheme.lower() != "bearer"
> ```
> 逐条对应：
> | Python | Java | 一致？ |
> |---|---|---|
> | `scheme.lower() != "bearer"` | `regionMatches(true, ...)` | ✅ 都忽略大小写 |
> | `param.strip()` | `.trim()` | ✅ 都去空白 |
> | `partition(" ")` 取第一个空格后的全部 | `substring(7)` | ✅ 等价 |
> | 缺 header → `("", "")` → 未认证 | `header == null` → `null` | ✅ 等价 |
>
> **所以 `bearer xxx`（小写）在两边都是通过、`Bearer  xxx`（双空格）在两边都能解析** ✓
> 这条原本被我列为"可疑分叉" ✓ 核对后**撤销** ✓ —— 记录在此是为了**避免下一个人重复查一遍** ✓

---

## 4e.5 `SchedulingSecurityConfig` —— 定规矩

### `schedulingSecurityFilterChain` —— 逐段读

```java
JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, authUserMapper);
```

⚠ **注释解释了为什么 `new` 而不注册成 Bean**：

> 直接 new，不注册成 Bean：Filter Bean 会被 Spring Boot 自动注册到 Servlet 容器，导致它在安全链之外也跑一遍

**这是一个很实际的坑** ✗：`OncePerRequestFilter` 的子类如果被注册成 `@Bean` ✓
Spring Boot 会把它当成**普通 Servlet 过滤器**自动注册 ✓ 于是它跑两次（一次在容器链、一次在安全链）✓
`OncePerRequestFilter` 的"一次"是按 `request.getAttribute` 判的 ✓ **两次注册用的是同一个属性名** ✓ 所以第二次会被跳过 ✓ —— **看起来"恰好没事"** ✓
但如果第二次注册的位置更靠前（在安全链之前 ✗）跑的就是"错误的那一次" ✓ 上下文在安全链里反而没设上 ✗ 行为就诡异了 ✓

**结论**：`new` 出来手动 `addFilterBefore` 是**正确且安全**的做法 ✓

```java
http
    .csrf(AbstractHttpConfigurer::disable)        // 无状态 API，不用 CSRF token
    .httpBasic(AbstractHttpConfigurer::disable)   // 不用 Basic 认证
    .formLogin(AbstractHttpConfigurer::disable)   // 不用表单登录页
    .logout(AbstractHttpConfigurer::disable)      // 没有会话，登出就是前端删 token
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**五个 `disable` 一次性说完**：这是一个**纯 token 的无状态 API** ✓
CSRF 的前提是"浏览器会自动带上凭证（cookie）" ✗ 而这里凭证在 `Authorization` 头里 ✓ 浏览器不会自动带 ✓ 所以 CSRF 不适用 ✓
`STATELESS` 则明确告诉 Spring"不要创建 `HttpSession`" ✓ —— 不设的话每个请求会建一个会话 ✗ 白白吃内存 ✓ 而且掩盖"这个服务本该无状态"这个事实 ✓

### 放行清单 —— 每一条都有理由

```java
.requestMatchers("/api/health",
        "/actuator/health", "/actuator/health/**",
        "/actuator/info", "/error").permitAll()
.requestMatchers("/internal/**").permitAll()
.anyRequest().authenticated()
```

| 路径 | 为什么放行 |
|---|---|
| `/api/health` | 对应 Python 的同一个接口 ✓ 健康检查必须能匿名访问 ✓ |
| `/actuator/health`、`/actuator/health/**` | K8s 探针 / 监控 ✓ `/**` 是给 `liveness` / `readiness` 子路径 ✓ |
| `/actuator/info` | 版本信息 ✓ 注意**只放行 `info`** ✓ 别的不放 ✓ |
| `/error` | ⚠ **这条最容易漏** ✗ Spring Boot 的内部错误转发路径 ✓ 不放行的话**任何错误都会变成 401** ✗ 你会看到"明明业务报 404，返回的却是 401" ✓ 极难查 ✓ |
| **`/internal/**`** | 🔴 见下 |
| `anyRequest().authenticated()` | 兜底：其余全部要认证 ✓ **白名单模式**（默认拒绝）✓ 而不是黑名单 ✓ |

#### 🔴 `/internal/**` 为什么 `permitAll`

注释写得很清楚：

> P3：worker 的内部接口。它们**不能**要求用户 JWT（worker 没有用户身份），所以在这里放行、由 `InternalJobController` 自己校验共享密钥 `X-Worker-Secret`。生产环境还应该在网络层限制（只有 worker 能访问），那是 P3 网关的职责。

**三层考虑逐层说**：
1. **为什么不能用 JWT**：worker 不是用户 ✓ 它没有登录流程 ✓ 让它持有用户 token 是错的（token 会过期 ✗ 而且等于给 worker 一个用户身份 ✗）
2. **为什么由控制器自己校验**：Spring Security 的认证模型是"身份" ✓ 而这里要表达的是"**调用方是内部服务**" ✓ 用共享密钥更直接 ✓
3. **⚠ 承认了这一层的不足**：密钥在网络上传输 ✓ 真正的防线是**网络隔离** ✓ 而那是网关的职责 ✓ 已明确写出来 ✓

> 💡 **这段注释的价值在于它没有夸大** ✓
> 它没说"共享密钥就够了" ✗ 而是明确指出"生产还应该在网络层限制" ✓
> **安全设计里，说清"我现在防住了什么、没防住什么"比声称"我很安全"重要得多** ✓

### 入口点与拒绝处理 —— 与过滤器配对

```java
.exceptionHandling(handling -> handling
        .authenticationEntryPoint(SchedulingSecurityConfig::writeUnauthorized)
        .accessDeniedHandler((request, response, ex) ->
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_DETAIL)))
```

| 回调 | 何时触发 | 回什么 |
|---|---|---|
| `authenticationEntryPoint` | **未认证**（上下文里没有认证对象） | 401 + 从请求属性里读到的**三种文案之一** |
| `accessDeniedHandler` | **已认证但权限不够** | 403 + `没有执行此操作的权限` |

#### `writeUnauthorized` —— 读回过滤器写的原因

```java
private static void writeUnauthorized(HttpServletRequest request,
                                      HttpServletResponse response,
                                      AuthenticationException exception) throws IOException {
    // 过滤器把失败原因放在请求属性里；没有就按"没带 token"处理
    Object reason = request.getAttribute(AuthFailure.ATTRIBUTE);
    String detail = reason instanceof String text ? text : AuthFailure.NO_TOKEN;
    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, detail);
}
```

**这就是"过滤器只记不说"的兑现点** ✓ 两头一连，三种文案就出来了 ✓

| 情况 | 请求属性 | 响应 |
|---|---|---|
| 没带 token | `请先登录` | 401 `{"detail":"请先登录"}` |
| token 不合法/过期 | `登录已失效，请重新登录` | 401 `{"detail":"登录已失效，请重新登录"}` |
| 用户不存在或停用 | `账号不存在或停用` | 401 `{"detail":"账号不存在或停用"}` |
| **属性没设**（兜底） | — | 401 `{"detail":"请先登录"}` |

⚠ **注意兜底选了 `NO_TOKEN` 而不是 `INVALID_TOKEN`** ✓
理由：属性没设说明**过滤器没跑到**（或跑到了但走了谁也没预料的分支 ✗）✓
而"没认证"最保守的解释就是"没凭证" ✓ 选 `NO_TOKEN` 语义上更中性 ✓

#### `writeJson` —— 为什么用 Jackson 而不是字符串拼接

```java
private static final ObjectMapper JSON = new ObjectMapper();
// ...
response.getWriter().write(JSON.writeValueAsString(java.util.Map.of("detail", detail)));
```

注释点出了理由：
> 用 Jackson 拼（而不是字符串相加），文案里有引号也不会破坏 JSON

**手工拼的话** ✗：`"{\"detail\":\"" + detail + "\"}"` ✓ 如果文案里有 `"` 就会拼出**非法 JSON** ✗
而前端 `JSON.parse` 会失败 ✗ 于是"错误信息本身变成了一个错误" ✓ —— 这类问题只在文案含特殊字符时出现 ✓ 极难复现 ✓

⚠ **两个小细节**：
1. `new ObjectMapper()` 是**裸的** ✓ 不带全局的 SNAKE_CASE 配置 ✗ —— 但这里只有一个全小写的 key `detail` ✓ 所以结果一样 ✓
   （对比 worker 那边踩过的坑：裸 `RestClient.builder()` 丢掉 Jackson 配置 ✓ 后果严重 ✗；**这里是安全的，因为只有一个 key** ✓ 但读的人应该知道"这里没有用全局配置" ✓）
2. `Map.of(...)` 保证只有一个键 ✓ 也保证**不输出 `null` 的键**（`Map.of` 不允许 null 值 ✓ 所以 `detail` 若为 null 会抛 NPE ✗ 而它永远不会是 null ✓ 上面已用 `instanceof` 兜过 ✓）

#### 403 文案与别处不一致 —— 这是有意的

```java
private static final String FORBIDDEN_DETAIL = "没有执行此操作的权限";
```

⚠ 注意这和 `BusinessException` 注释里记的 Python 原文**不一样**：

| 出处 | 文案 |
|---|---|
| Python（`BusinessException` 的 javadoc 记录） | `仅管理员可以执行此操作` |
| **这里（安全网）** | `没有执行此操作的权限` |

**为什么不一致也是对的** ✓：
- **真正的权限检查写在各个 Controller 里** ✓（调 `principal.isAdmin()` ✓ 抛 `仅管理员可以执行此操作` ✓）
- 这一层是**安全网** ✓ 只有"某个接口忘了做权限检查、但 Spring 的 `hasRole` 拦住了"才会触发 ✓
- **安全网触发本身就是个 bug 信号** ✓ 所以它的文案**不该**伪装成正常的业务提示 ✓

> 💡 换句话说：**这一层越不常出现越好** ✓ 它出现了就说明代码有遗漏 ✓
> 让它说一句**不同于**业务文案的话，反而是对的 ✓

---

## 4e.6 `SchedulingInfraConfig` —— 两个 bean

```java
@Bean
public JwtService jwtService(@Value("${orbit.jwt.secret}") String secret,
                             @Value("${orbit.jwt.ttl-seconds:28800}") long ttlSeconds) {
    return new JwtService(secret, ttlSeconds);
}

@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

### `JwtService` —— scheduling **只用它的校验**，为什么还要 TTL

类注释交代了：
> `JwtService` 在这里**只用于校验**（scheduling 不签发 token），但它的构造器要求一个 TTL，所以照样从配置读 —— 保持两个服务的装配方式一致，将来若 scheduling 也需要签发（例如 worker 回调）就不用改结构。

**这是"装配对称性"** ✓：两个服务用**同一个类、同一份配置键** ✓
代价是多读一个用不上的配置 ✓ 收益是"看到 scheduling 的配置就知道它认哪个密钥、token 多久过期" ✓

⚠ 而 `orbit.jwt.secret` **没有默认值** ✓ —— 与 `ttl-seconds:28800` 形成对比 ✓
**这是刻意的**：密钥缺失必须**启动失败** ✓ 而不是安静地用一个人人都知道的默认值 ✗
（`JwtService` 的构造器还会再检查一次长度 ≥32 字节 ✓ 双重防线 ✓）

### `PaginationInnerInterceptor` —— 不注册它会**静默不分页**

注释：
> 分页拦截器与 academic 同理：不注册它 `selectPage` 就**静默不分页**（见踩坑总结的 H6）。版本列表接口需要分页，所以必须有。

⚠ **这是本项目最高频的一类坑**：MyBatis-Plus 的 `selectPage` **不注册拦截器时不报错** ✗
它会**照常执行查询** ✓ 但**忽略分页参数** ✓ 返回**全部数据** ✓ `total` 也是错的 ✓
**你不看数据量根本发现不了** ✓（4 个班的时候完全看不出来 ✓ 160 个班时才炸 ✓）

`DbType.MYSQL` 必须显式给 ✓ —— 不给的话 MyBatis-Plus 会去猜数据库类型 ✓ 多一次连接元数据查询 ✓ 而且猜错的后果同样是静默的 ✓

> 💡 **P3 的 `JobController` 分页**（5b 册）正是靠这个拦截器工作的 ✓
> 如果哪天有人把这个 bean 删了（"看起来没人用"）✗ 任务列表会开始返回全表 ✗ 而且**不报错** ✓
> 这就是为什么这类"基础设施 bean"的注释必须写清"删了会怎样" ✓ —— 它做到了 ✓

---

## 4e.7 `SchedulingApplication` —— 两个注解，各有一个"不写就静默出错"的后果

```java
@SpringBootApplication(scanBasePackages = {"com.orbit.scheduling", "com.orbit.common"})
@MapperScan(basePackages = "com.orbit.scheduling", annotationClass = Mapper.class)
public class SchedulingApplication { ... }
```

类注释把两个坑都写明了：

### ① `scanBasePackages` 不写会怎样

> `com.orbit.common` 不显式加进来的话，`ApiExceptionHandler`（统一错误体）**不会被注册** —— 错误响应会退回 Spring 默认的 `ProblemDetail`，**而且不报错**

⚠ 后果链条值得展开：
- `@SpringBootApplication` 默认只扫**启动类所在包及其子包** ✓ 也就是 `com.orbit.scheduling.**` ✓
- `com.orbit.common` 是**另一个顶层包** ✗ 扫不到 ✓
- 而 `ApiExceptionHandler` 上有 `@RestControllerAdvice` ✓ 没被扫到就不生效 ✓
- 于是所有错误响应悄悄变回 `ProblemDetail` ✗（`{"type":"about:blank","title":...,"status":404,...}` ✓）
- **前端读的是 `error.response.data.detail`** ✓ 而 `ProblemDetail` 里**也有** `detail` ✗ → **页面居然还能工作** ✗ 只是多了一堆字段 ✓
- 只有**契约测试**会发现（字段多了 ✓）✓ —— 这是影子对比工具的价值之一 ✓

### ② `@MapperScan` 的两个参数都不能省

> 必须配 `annotationClass = Mapper.class`：本项目的约定是 Mapper 与实体同放一个领域包（不是 `*.mapper` 子包），所以只按包名扫会漏；而不加过滤又会把将来的服务接口误注册成 Mapper

**两个方向的风险都说了**：

| 写法 | 后果 |
|---|---|
| 不写 `@MapperScan` | ⚠ 如果每个 Mapper 都带 `@Mapper` 注解 ✓ MyBatis 的自动扫描也能发现它们 ✓ —— 但**依赖"每个都记得加"** ✓ 漏一个就是"启动时报找不到 bean" ✓（还算好查 ✓） |
| `@MapperScan("com.orbit.scheduling")`（不带过滤） | Mapper 与实体同包 ✗ 于是包里的**所有接口**都被当 Mapper 注册 ✗ 包括将来的 Service 接口、`SnapshotMappers` 里的嵌套接口 ✗ 注入时会拿到 MyBatis 代理对象 ✓ 调用才炸 ✓ **最难查的一类** ✓ |
| **`annotationClass = Mapper.class`** | 只注册**显式标了 `@Mapper`** 的接口 ✓ 双重保险 ✓ |

> ⚠ 一个值得注意的细节：`SnapshotMappers` 里那 7 个嵌套接口**每个都标了 `@Mapper`** ✓
> —— 现在明白为什么了：**因为没有标就不会被注册** ✓ 它们的 `@Mapper` 不是装饰 ✓ 是必需的 ✓

---

## 本册小结

这 7 个类加起来不到 400 行，但它们是**这个服务的门**：

| 类 | 它守的东西 |
|---|---|
| `AuthUser` | **只读 + 不含口令哈希** —— 用类型系统做安全边界 |
| `AuthUserMapper` | 只读门面不需要自定义 SQL |
| `SchedulePrincipal` | 不做无用的 `UserDetails` 实现 |
| `JwtAuthenticationFilter` | **只记原因、不做拒绝** —— 因为放行是授权层的决定 |
| `SchedulingSecurityConfig` | 白名单放行 + 三种 401 文案 + `/internal/**` 的取舍 |
| `SchedulingInfraConfig` | 分页拦截器（**删了会静默失效**）+ 密钥必填 |
| `SchedulingApplication` | 扫描范围（**漏了会静默退回 `ProblemDetail`**） |

**读这一册最该带走的两件事**：

1. **`/error` 必须放行** ✗ —— 不放行的话**任何错误都会变成 401** ✓ 极难查 ✓
2. **`AuthFailure` 请求属性不是多此一举** ✓ —— 它跨越的是"认证"与"授权"的职责边界 ✓
   过滤器不能自己回 401 ✓ 因为有些路径本来就不需要认证 ✓

### 本册发现的问题（4 处，都没动代码）

| # | 问题 | 性质 |
|---|---|---|
| ~~1~~ | ~~`bearerToken` 的大小写/空白处理可能与 Python 分叉~~ | ✅ **已核对，撤回** ✓ 见 §4e.4：Python 用 FastAPI 的 `HTTPBearer`，它的 `scheme.lower()` + `param.strip()` 与 Java 的 `regionMatches(true,…)` + `.trim()` **逐条对应** ✓ 两边一致 ✓ |
| **2** | 停用账号时调了 `SecurityContextHolder.clearContext()` ✗ **但没有注释说明为什么** | 代码是**对的** ✓（不清的话旧认证会残留 ✗ 是个安全洞）✓ 但真正的原因没写下来 ✓ 后来者可能"顺手删掉这行看起来多余的调用" ✗ |
| **3** | `role.toUpperCase()` **没指定 `Locale`** | 当前 `admin`/`platform_admin`/`operator` 都不含 `i` ✓ 无害 ✓ 但加一个含 `i` 的角色就会在土耳其语环境静默失效 ✓ 规范写法 `toUpperCase(Locale.ROOT)` ✓ |
| **4** | 403 文案与 Python 的 `仅管理员可以执行此操作` **不一致** | 我认为**这是对的**（安全网不该伪装成业务提示）✓ 但文档里没有说明这处差异是**有意的** ✗ 建议补一句注释 ✓ |
