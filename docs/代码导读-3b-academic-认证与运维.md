# Orbit 代码导读（三·b）—— `orbit-academic`：认证 / 组织 / 账号 / 运维

> **与其它文档的分工**
> - [架构说明.md](架构说明.md)：讲「为什么这么分、各部分怎么协作」。
> - [代码导读.md](代码导读.md)（**第 1 篇**，`orbit-common` 10 个类）：本篇的**格式 / 语气 / 详细程度基准**，
>   也是本篇的前置知识 —— `BusinessException` / `ApiExceptionHandler` / `JwtService` / `AuthFailure`
>   这四个类的逐行讲解都在那边，本篇只讲**怎么用它们**，不重复讲它们内部。
> - **本篇（3b）**：`orbit-academic` 模块里的**认证 / 组织 / 账号 / 运维 / config / JWT 过滤器**。
> - 3a：`orbit-academic` 的**教务主数据 CRUD**（年级 / 班级 / 科目 / 年级周课时 / 教师 / 任教 / 固定课）——不在本篇。

**本篇覆盖清单**（27 个 main 类 + 1 个测试类，全部逐个读完，不是按类名猜的）：

| 包 | 类 | 行数 | 一句话 |
|---|---|---|---|
| `auth` | `PythonPbkdf2PasswordEncoder` | 112 | 复刻 Python 的 `pbkdf2_sha256$310000$盐$摘要` |
| `auth` | `LoginAttemptGuard` | 114 | 登录失败限流（Redis 计数 + `Retry-After`） |
| `auth` | `OrbitUserDetailsService` | 60 | 按用户名 / 按 id 装载用户 |
| `auth` | `OrbitUserPrincipal` | 161 | 认证主体（比 Spring 自带的 `User` 多 3 个业务字段） |
| `auth` | `AuthController` | 179 | `/api/auth/*` 3 个接口 |
| `auth` | `LoginRequest` / `LoginResponse` / `PasswordChangeRequest` | 27 / 21 / 21 | 进出 HTTP 的 JSON 形状 |
| `auth` | `JwtAuthenticationFilter` | 101 | 从 `Authorization` 头恢复认证上下文 |
| `auth` | `PythonPbkdf2PasswordEncoderTest`（test） | 127 | **跨语言兼容性**测试（最不能出错的一段） |
| `organization` | `Organization` / `OrganizationMapper` / `OrganizationResponse` | 48 / 29 / 19 | 租户表 |
| `organization` | `OrganizationCreateRequest` / `OrganizationService` / `OrganizationController` | 32 / 162 / 70 | 工作空间接口 |
| `user` | `User` / `UserMapper` / `UserResponse` | 116 / 22 / 58 | `users` 表 |
| `user` | `UserCreateRequest` / `UserUpdateRequest` / `UserService` / `UserController` | 26 / 10 / 181 / 66 | 账号接口 |
| `health` | `HealthController` | 139 | `GET /api/health` |
| `config` | `SecurityConfig` / `JwtConfig` / `MybatisPlusConfig` | 150 / 29 / 42 | 装配 |
| 根 | `AcademicApplication` | 50 | 启动类（包扫描 + Mapper 扫描） |

---

## 0. 本模块在系统里的位置

### 0.1 三个定位

**① 它是"这所学校长什么样 + 谁能进来"的唯一权威。**
它属主 10 张表（[V1__baseline_legacy.sql](../backend-java/orbit-academic/src/main/resources/db/migration/academic/V1__baseline_legacy.sql)）：
`organizations` `users` `grades` `subjects` `classes` `grade_subjects` `teachers`
`teacher_assignments` `grade_fixed_slots` `fixed_slots`。
其中本篇负责的是**前两张 + 它们的接口**。

**② 它是全项目唯一签发 token 的服务（:8081）。**
证据是"有什么类"而不是文档：

| | `orbit-academic` | `orbit-scheduling` |
|---|---|---|
| `PasswordEncoder` | ✅ `PythonPbkdf2PasswordEncoder` | ❌ 没有 |
| `AuthenticationManager` | ✅ 有 | ❌ 没有 |
| `/api/auth/login` | ✅ 有 | ❌ 没有 |
| `JwtService`（签发） | ✅ 调 `issue()` | ❌ 只调 `userIdOf()` |
| JWT 密钥 | `APP_SECRET_KEY` | **同一把** `APP_SECRET_KEY` |

所以：**一个服务签发、两个服务都认**。scheduling 侧的对照见
[SchedulingSecurityConfig.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/config/SchedulingSecurityConfig.java)
（它的类注释第一句就是「**只校验 token，没有登录流程**」）。

**③ 它的运维面只有 `GET /api/health` 一个接口。**（见 §0.3 的范围更正）

### 0.2 一次登录 + 一次带 token 请求（先看这张图，再看代码）

```
【登录】POST /api/auth/login  {username, password}
   │
   ├─ AuthController.login
   │     ① clientIp = request.getRemoteAddr()
   │     ② LoginAttemptGuard.retryAfterSeconds(username, ip)
   │           └─ Redis GET orbit:login_failures:<sha256(user|ip)>
   │              ≥5 次 → 读该 key 的 TTL → throw 429
   │                （BusinessException.tooManyRequests → 响应头 Retry-After）
   │     ③ AuthenticationManager.authenticate(用户名, 口令)
   │           └─ DaoAuthenticationProvider
   │                ├─ OrbitUserDetailsService.loadUserByUsername → 查 users 表
   │                ├─ PythonPbkdf2PasswordEncoder.matches(口令, 库里的哈希)
   │                └─ 停用（isEnabled()==false）→ DisabledException
   │     ④ 成功 → LoginAttemptGuard.clear(...)
   │     ⑤ JwtService.issue(userId) → 标准 JWT（sub=userId, jti=nonce）
   │     ⑥ LoginResponse.of(token, UserResponse) → {"access_token","token_type":"bearer","user"}

【带 token 请求】GET /api/auth/me   Authorization: Bearer <token>
   │
   ├─ JwtAuthenticationFilter（安全链里，在 UsernamePasswordAuthenticationFilter 之前）
   │     无 token      → request.setAttribute(AuthFailure.ATTRIBUTE, "请先登录")
   │     token 非法/过期 → ... "登录已失效，请重新登录"
   │     token 有效    → OrbitUserDetailsService.loadById(uid)
   │                     ├─ 查得到 → SecurityContextHolder.setAuthentication(...)
   │                     └─ 查不到 / 已停用 → "账号不存在或停用" + clearContext
   ├─ authorizeHttpRequests：这张表里 /api/auth/me 没被 permitAll → 要求已认证
   └─ 未认证 → SecurityConfig.writeUnauthorized 读上面的请求属性 → 401 {"detail": …}
```

### 0.3 ⚠ 范围更正：两个"任务书里提到、但不在本模块"的东西

动笔前逐文件确认过（`grep` 全 `backend-java`），这两处**都不是** `orbit-academic` 的代码：

| 提到的东西 | 实际位置 | 说明 |
|---|---|---|
| `/api/system/*` 三个运维接口 | `orbit-scheduling/system`（`SystemController` / `SystemService` / `SystemStatsMapper`） | academic 里**没有** `system` 包；academic 的运维接口是 `health/HealthController`（`GET /api/health`），本篇照实讲它 |
| `"/internal/**"` 的 `permitAll` | `orbit-scheduling/config/SchedulingSecurityConfig` | academic 的 `SecurityConfig` 里**没有** `/internal/**` 这条规则（它没有 worker 内部接口） |

为了不把读者引到错的地方：本篇 §4.5 单独用一小节讲清 `/internal/**` 为什么 `permitAll`，
但**明确标出那是 scheduling 的代码**。

### 0.4 本模块（本篇范围）的接口清单

| 方法 | 路径 | 要求 | 实现 |
|---|---|---|---|
| POST | `/api/auth/login` | **匿名**（`permitAll`） | `AuthController.login` |
| GET | `/api/auth/me` | 已认证 | `AuthController.me` |
| POST | `/api/auth/change-password` | 已认证 | `AuthController.changePassword` |
| GET | `/api/organizations` | **平台管理员** | `OrganizationController.list` |
| POST | `/api/organizations` | **平台管理员** | `OrganizationController.create` |
| GET | `/api/users` | **管理员** | `UserController.list` |
| POST | `/api/users` | **管理员** | `UserController.create` |
| PUT | `/api/users/{userId}` | **管理员** | `UserController.update` |
| GET | `/api/health` | **匿名** | `HealthController.health` |

⚠ 注意"管理员/平台管理员"是**两种不同的文案**，不能混（见 §5.6 与 §6.6）：
`仅管理员可以执行此操作` vs `仅平台管理员可以管理工作空间`。

---

## 1. 阅读顺序建议

本篇比第 1 篇细（要求是"逐行"），一次读完会很累。建议分四趟：

| 趟 | 读什么 | 为什么这个顺序 |
|---|---|---|
| **第 1 趟（先读，最重要）** | §4 `SecurityConfig` → §3 `JwtAuthenticationFilter` | 先建立「一个请求怎么变成已认证 / 未认证时谁写 401」的骨架。**不读这两个，后面所有的 `@AuthenticationPrincipal` 都是魔法** |
| **第 2 趟** | §2 认证链（`PythonPbkdf2PasswordEncoder` → `LoginAttemptGuard` → `OrbitUserDetailsService` → `OrbitUserPrincipal` → `AuthController`） | 登录是本篇唯一一条"多组件咬合"的链，逐层往下读一次就通了 |
| **第 3 趟** | §5 组织、§6 账号 | 这两个包是**标准 CRUD**，套路与 3a 主数据完全一样，可以快读；重点只看三处：`OrganizationService` 的**故意不写 `scheduling_state`**、`UserService.update` 的**两条自我保护**、以及 `UserResponse` 的 `is_active` 命名坑 |
| **第 4 趟** | §7 运维、§8 config、§9 差异清单 | 收尾。§9 是"拿去核对/回归"的清单，改代码前扫一眼 |

**只想改一个地方时怎么看**：

| 我想改… | 直接跳到 |
|---|---|
| 密码算法 / 迭代次数 | §2.1（⚠ 先读那里的 4 条"注释与代码不一致"） |
| 登录限流的次数 / 窗口 / 文案 | §2.2（次数与窗口是 `application.yml` 里的 `orbit.login.*`） |
| 401 文案 | §3（过滤器写原因）+ §4.4（入口点选文案） |
| 放行路径 | §4.3 |
| 权限（谁能调） | §5.6 / §6.6（**不在** `SecurityConfig` 里，是代码里的显式检查） |
| 健康检查返回什么 | §7 |

---

## 2. 认证链（`auth` 包）

### 2.0 先记住三层职责

```
AuthController        —— HTTP 形状：状态码、文案、Retry-After、返回体
   ↓
AuthenticationManager —— Spring 的标准流程：查用户 + 比口令 + 停用判断
   ↓
PasswordEncoder / UserDetailsService —— 与 Python 库兼容的那两层
```

**为什么不让 Controller 自己 `verify_password`**（像 Python 那样）：
`SecurityConfig` 的类注释写了理由 —— 在 Spring 里那等于把框架已经做好的事重做一遍。
但**对外行为必须逐字对齐**

---

### 2.1 `PythonPbkdf2PasswordEncoder` —— 密码存储格式（112 行）

文件：[PythonPbkdf2PasswordEncoder.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/PythonPbkdf2PasswordEncoder.java)

**它为什么存在**：`users.password_hash` 列里已经有 Python 生成的哈希。
换成 BCrypt 或者 Spring 自带的 `Pbkdf2PasswordEncoder`（默认格式 `{pbkdf2@SpringSecurity_v5_8}…`），
**所有老用户都登录不了** —— 不是报错，是 `matches()` 永远返回 false。

#### 存储格式（库里的字符串长这样）

```
pbkdf2_sha256 $ 310000 $ 00112233445566778899aabbccddeeff $ b847df49…ee3b
      ↑            ↑              ↑                                  ↑
   算法名      迭代轮数       盐（16 字节 → 32 个 hex）        摘要（32 字节 → 64 个 hex）
```

| 项 | Python（[auth.py](../backend/auth.py)） | 这里 | 必须一致吗 |
|---|---|---|---|
| 算法 | `hashlib.pbkdf2_hmac("sha256", …)` | `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` | ✅ HMAC-SHA256 |
| 轮数 | `PBKDF2_ITERATIONS = 310_000` | `ITERATIONS = 310_000`（`public static final`） | 见下面的 ⚠ ① |
| 盐 | `secrets.token_bytes(16)` | `SecureRandom.nextBytes(new byte[16])` | 16 字节 |
| 摘要 | 32 字节 → `hex()`（小写） | `HexFormat.of().formatHex(...)`（小写） | 32 字节 |
| 口令编码 | `password.encode()` = UTF-8 | `PBEKeySpec(password.toCharArray(), …)` | 见 ⚠ ③ |
| 比较 | `hmac.compare_digest`（恒定时间） | `MessageDigest.isEqual`（恒定时间） | ✅ 都恒定时间 |
| 最短长度 | `len(password) < 8` → `ValueError("密码至少需要 8 位")` | `< 8` → `IllegalArgumentException("密码至少需要 8 位")` | ✅ 连文案都同 |

#### 逐方法

**`String encode(CharSequence rawPassword)`**（第 58–70 行）

| 顺序 | 代码 | 在做什么 / 为什么 |
|---|---|---|
| 1 | `rawPassword == null ? "" : rawPassword.toString()` | null 归一成空串 → 会走进下面的长度检查并抛异常（而不是 NPE → 500） |
| 2 | `if (password.length() < MIN_PASSWORD_LENGTH) throw new IllegalArgumentException("密码至少需要 8 位")` | ⚠ **长度检查在这里，不在 DTO 上**。原因见 [PasswordChangeRequest.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/PasswordChangeRequest.java) 的注释：写在 DTO 上会被 Jakarta 校验变成 **422**，而 Python 是 `except ValueError → 400`。**状态码不同就是契约不同** |
| 3 | `random.nextBytes(salt)` | `SecureRandom` 是**实例字段**（第 56 行），不是每次 `new` —— 复用同一个 `SecureRandom` 是正常做法（它自身线程安全） |
| 4 | `derive(password, salt, ITERATIONS)` | 真正算 PBKDF2 |
| 5 | `ALGORITHM + "$" + ITERATIONS + "$" + hex(salt) + "$" + hex(digest)` | 拼成 Python 的格式。**新哈希也用老格式**：迁移期 Python 侧（还在跑）或其他工具读这个库时也必须能校验 |

**`boolean matches(CharSequence rawPassword, String encodedPassword)`**（第 72–91 行）

| 顺序 | 代码 | 在做什么 / 为什么 |
|---|---|---|
| 1 | `rawPassword == null \|\| encodedPassword == null → false` | 不抛。对应 Python 的 `except TypeError: return False` |
| 2 | `encodedPassword.split("\\$", EXPECTED_PARTS)`（`EXPECTED_PARTS = 4`） | ⚠ 这个 `4` 是 **limit**（最多切 3 刀 → 最多 4 段），语义与 Python 的 `encoded.split("$", 3)` **完全一致**：摘要里万一混进 `$` 也不会被切碎，而是留在第 4 段里 |
| 3 | `parts.length != 4 \|\| !"pbkdf2_sha256".equals(parts[0]) → false` | 算法名不对（比如库里掺了 BCrypt 行）→ 直接 false |
| 4 | `int iterations = Integer.parseInt(parts[1])` | ⚠⚠ **轮数是从哈希串里读的，不是用常量**。见 ⚠ ① |
| 5 | `HexFormat.of().parseHex(parts[2])` / `parseHex(parts[3])` | 盐与摘要。见 ⚠ ② |
| 6 | `derive(rawPassword.toString(), salt, iterations)` | 用**串里的**轮数重算 |
| 7 | `MessageDigest.isEqual(actual, expected)` | 恒定时间比较，避免计时侧信道（对应 `hmac.compare_digest`） |
| 8 | `catch (IllegalArgumentException e) → false` | `NumberFormatException` 与 `HexFormat` 的报错**都是** `IllegalArgumentException` 的子类，所以这一个 catch 覆盖「轮数不是数字」「盐不是合法 hex」「hex 长度是奇数」三种坏数据。**一个坏行不能让整个登录接口 500** |

**`private static byte[] derive(String password, byte[] salt, int iterations)`**（第 93–102 行）

- `new PBEKeySpec(password.toCharArray(), salt, iterations, 256)` → `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()`
- `finally { spec.clearPassword(); }` —— 把 `PBEKeySpec` 内部那份 `char[]` 副本清零。
  ⚠ **但原始 `String` 清不掉**（Java 的 `String` 不可变），所以这只是"少留一份拷贝"，不是彻底的安全措施。
- JCE 不可用时抛 `IllegalStateException("PBKDF2 不可用（JCE 环境异常）")` —— 这是**环境坏了**，不是口令错，所以不吞成 `false`。

**`static String digestHex(String, byte[], int)`**（第 104–107 行）：包级可见，**只被测试调用**
（[PythonPbkdf2PasswordEncoderTest.digestMatchesPython](../backend-java/orbit-academic/src/test/java/com/orbit/academic/auth/PythonPbkdf2PasswordEncoderTest.java) 用它断言"同盐同口令下 Java 算出的摘要与 Python 逐字符相同"）。

**`static byte[] utf8(String)`**（第 109–111 行）：⚠ **没有调用点**（`grep` 全 `backend-java` 只有定义处）。
看名字是给"UTF-8 编码"预留的（当时大概想在 `derive` 里自己编码），现在 `derive` 走的是 `PBEKeySpec`，
所以它是死代码。**不要以为它说明"这里显式用了 UTF-8"** —— 编码是由 JCE provider 决定的。

#### ⚠ 本章发现的四条"注释与代码不一致 / 需要当心的地方"

这四条都是**逐行读两边代码**读出来的（不是推测，但也**没有**跑运行时验证，所以不标"实测"）。

**⚠ ① 改 `ITERATIONS` 常量并不会让旧哈希校验失败 —— 与注释说的不一样。**

- `encode()` 用常量 `ITERATIONS` 生成新哈希；
- 而 `matches()` 用的是 **`parts[1]`（哈希串里存的轮数）**，第 82 行 `Integer.parseInt(parts[1])`，
  第 85 行把它传给 `derive`。
- Python 的 `verify_password` 同样用 `int(iterations)`（[auth.py](../backend/auth.py) 第 36 行）。

⇒ **代码的实际行为是**：把常量从 310000 改成别的值，**旧哈希照样能校验通过**，
只是"新建的哈希轮数与旧的不同"，而两边都能各自校验（因为轮数随哈希一起存）。
类注释与 `ITERATIONS` 的 javadoc 里"改了就校验不了旧哈希"**是过度表述**。
真正必须保持一致的是：**算法名 `pbkdf2_sha256` + 4 段格式 + hex 摘要长度**；
而 `ITERATIONS` 之所以仍要对齐 310000，是**为了不悄悄削弱新口令的强度**（降低轮数不会报错、也没有测试会红）。

**⚠ ② hex 的大小写：Java 比 Python 宽松一格。**
Python 比的是**字符串**：`hmac.compare_digest(actual.hex(), expected)` —— `actual.hex()` 永远小写，
所以库里若存了大写 hex，Python 会判**不通过**。
Java 比的是**字节**：`HexFormat.of().parseHex(...)` 两种大小写都收，再逐字节比 —— 会判**通过**。
实际库里（Python `hashlib` 生成）都是小写，所以影响面为零；
但"手工改过一行哈希"这种场景两边行为会不同。

**⚠ ③ 口令 → 字节的编码路径变了，emoji 那条路没有被测试真正覆盖。**
Python 是 `password.encode()`（明确 UTF-8）。
Java 是把 `String.toCharArray()` 交给 JCE，**由 provider 决定怎么编码**（SunJCE 对 BMP 字符用 UTF-8，
所以中文与 Python 一致 —— 测试里的 `密码测试12345` 就是这样验证的）。
但**增补平面字符（emoji，UTF-16 里是代理对）**是否与 Python 的 UTF-8 编码逐字节相同，
（此处未完全理解 JDK provider 对代理对的处理，代码行为是：由 `PBEKeySpec` + SunJCE 决定）。
⚠ 注意测试方法的 `@DisplayName` 写的是「UTF-8 口令（中文/**emoji**）也能校验」，
但方法体里只有中文口令，**没有 emoji**。
**要确认这件事，最小的做法是：用 Python 生成一条含 emoji（如 `🔒`）的固定向量，加一条断言。**

**⚠ ④ `UPPERCASE` hex 与 `parts.length` 已在上面的表格里说清，这里只留一条实践建议**：
库里出现任何"格式对不上"的行时，**行为是"这个人登不进来"，而不是"接口 500"** ——
这是有意的（第 41–42 行注释），排查时要往"这一行的 `password_hash` 是不是坏的"方向想，
而不是去查登录接口。

---

### 2.2 `LoginAttemptGuard` —— 登录失败限流（114 行）

文件：[LoginAttemptGuard.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/LoginAttemptGuard.java)
Python 对应：[redis_service.py](../backend/redis_service.py) 第 70–108 行（`_login_key` / `login_retry_after` / `record_login_failure` / `clear_login_failures`）

**契约**：连续失败 **5** 次（`LOGIN_MAX_FAILURES`）后，登录接口返回 **429** +
`{"detail":"登录失败次数过多，请在 N 秒后重试"}` + **`Retry-After: N`** 响应头；窗口 **300** 秒。

#### 为什么 Redis key 要**逐字**与 Python 一致（这是本篇最值得记住的设计）

```
orbit:login_failures:<sha256( username.strip().lower() + "|" + client_ip )>
```

**两边共用同一个 Redis**。key 一致 ⇒ **迁移期双跑的计数是共享的**：
攻击者在 Python 上试 3 次、在 Java 上试 3 次，**总共算 6 次**，第 6 次就被挡（429）。
如果 key 不一致，**限流在切换期间会被绕过**。
这条也是契约测试里那个大坑的来源（见 §2.7 之后的说明与
[compare.py](../tools/contract-diff/compare.py) 第 462–465 行）：**同一用户名混着打两边会互相累加**，
所以测限流必须给两边用**不同的用户名**，否则测出来的"第 6 次"根本不对。

#### 逐方法

**构造器**（第 45–51 行）
```java
LoginAttemptGuard(StringRedisTemplate redis,
                  @Value("${orbit.login.max-failures:5}") int maxFailures,
                  @Value("${orbit.login.window-seconds:300}") int windowSeconds)
```
- 三个参数：Redis 模板 + 两个可配阈值。⚠ **`application.yml` 里当前并没有 `orbit.login.*` 这两项**
  （只有 `orbit.jwt.*` 与 `orbit.tenant.*`），所以走的是注解里的默认值 5 / 300。
  想改限流阈值，**可以不改代码**，在 yml 或环境变量里加上 `orbit.login.max-failures` / `orbit.login.window-seconds` 即可。
- 依赖直接注入 `StringRedisTemplate`（而 `HealthController` 用的是 `ObjectProvider` —— 见 §7 的对比）：
  这里**不能**用 `ObjectProvider` 的"没有就跳过"语义，因为 Redis 挂掉要靠 `try/catch` 降级。

**`int retryAfterSeconds(String username, String clientIp)`**（第 60–73 行）—— **`Retry-After` 就算在这里**

| 顺序 | 代码 | 在做什么 |
|---|---|---|
| 1 | `redis.opsForValue().get(key(username, clientIp))` | 读计数（字符串） |
| 2 | `value == null ? 0 : Integer.parseInt(value.trim())` | ⚠ **`trim()`**：Python 是 `int(client.get(key) or 0)`，Redis 返回的字符串一般没有空白；Java 侧多一次 `trim` 是防御 |
| 3 | `if (failures < maxFailures) return 0;` | 不足 5 次 → `0` 表示"可以试"。**0 与"要等"是一对相反的语义**，所以下一段必须保证不返回 0 |
| 4 | `Long ttl = redis.getExpire(key(...))` | `TTL key` —— 窗口还剩多少秒 |
| 5 | `return (ttl == null \|\| ttl <= 0) ? 1 : ttl.intValue();` | ⚠ **最小返回 1**。这是 Python `max(int(client.ttl(key)), 1)` 的等价写法：<br>`TTL` 对**不存在的 key** 返回 `-2`、对**没有过期时间的 key** 返回 `-1`。Python 的 `max(-2, 1)` 得 1；这里 `ttl <= 0 → 1`，**同一个结果**。<br>**为什么必须有这个下限**：调用方是 `if (retryAfter > 0)`，如果这里返回 0，就会**放行** —— 于是"已经超限"变成"随便试" |
| 6 | `catch (Exception e) { log.warn(...); return 0; }` | **降级放行**（见下） |

**`void recordFailure(String username, String clientIp)`**（第 76–84 行）
```java
redis.opsForValue().increment(key);              // INCR
redis.expire(key, Duration.ofSeconds(windowSeconds));  // EXPIRE
```
- 对应 Python 的 `pipe.incr(key); pipe.expire(key, LOGIN_WINDOW_SECONDS); pipe.execute()`。
- ⚠ **语义要点：每次失败都重设窗口**（`EXPIRE` 把 TTL 重置为 300），所以窗口是"**滑动**"的：
  一直失败就一直被锁着；停止失败 300 秒后才解锁。这**不是**"5 分钟内累计 5 次"的固定窗口。
- ⚠ **这里是本方法唯一的实现差异**：Python 用的是 **pipeline（MULTI/EXEC，两条命令原子执行）**，
  Java 这里是**两次独立往返**。若 `INCR` 成功而 `EXPIRE` 失败（或进程在这两行之间挂掉），
  会留下一个**永不过期的计数 key** → 该 (用户名, IP) 组合被**永久限流**。
  （这是从代码读出来的风险，**没有实测复现**；真要修，用 `RedisTemplate.execute` 的 pipeline 或
  一条 Lua 脚本 `INCR` + `EXPIRE` 即可。）
- `catch (Exception)` 只 `log.warn` 不抛 —— 记失败次数失败不应该让登录接口 500。

**`void clear(String username, String clientIp)`**（第 87–93 行）：登录成功后 `redis.delete(key)`，
对应 Python 的 `clear_login_failures`。同样吞异常。

**`private static String key(String username, String clientIp)`**（第 96–99 行）—— **逐字复刻 `_login_key`**

```java
String identity = safe(username).strip().toLowerCase(Locale.ROOT) + "|" + safe(clientIp);
return "orbit:login_failures:" + sha256Hex(identity);
```

| 细节 | 为什么 |
|---|---|
| `strip()` 而不是 `trim()` | Java 的 `strip()` 按 Unicode 空白（与 Python 的 `str.strip()` 一致），`trim()` 只认 ASCII ≤ U+0020。⚠ **两者混用是本项目一个容易踩的漂移点**（`OrbitUserDetailsService` 用的就是 `trim()`，见 §2.3） |
| `toLowerCase(Locale.ROOT)` | Python 的 `lower()` 是无 locale 的。用 `Locale.ROOT` 避免土耳其语环境下 `I` → `ı` 的差异。**这是有意的** |
| `safe(...)`：null → `""` | Python 侧若传 `None` 会拼出 `"none"`；Java 里 `null` 的语义是"没有这个值"，归空串。**这条两边不等价**，但调用方（`AuthController`）已经保证 username 非 null，`clientIp` 也保证非 null（`"unknown"`） |
| `sha256Hex` 的输入是 UTF-8 字节 | Python 是 `hashlib.sha256(identity.encode()).hexdigest()`（UTF-8）。**一致** |

**`private static String sha256Hex(String)`**（第 105–113 行）：`MessageDigest.getInstance("SHA-256")` +
`HexFormat.of().formatHex(...)`（小写 hex）。
`NoSuchAlgorithmException` 抛 `IllegalStateException` —— SHA-256 不可能不存在，这是环境坏了。

#### ⚠ Redis 不可用时"降级放行"是有意的，但**启动期的强校验没有搬过来**

- 这里每个方法都 `catch (Exception) → 返回"不限流"`，注释写了理由：
  **限流失败不应该让登录功能整体不可用**（可用性优先于这个非核心防护）。
- 注释同时指出：**"真正的约束是启动时的 `REDIS_REQUIRED=true`"**（Python
  [redis_service.py](../backend/redis_service.py) 的 `verify_redis_configuration`）。
- ⚠ **但 Java 侧没有这个启动校验**：`grep REDIS_REQUIRED backend-java` 只命中
  `LoginAttemptGuard` 的**注释文字**，没有任何实现。
  也就是说，Python 配了 `REDIS_REQUIRED=true` 时 Redis 挂了会**启动失败**，
  Java 侧会**照常启动、限流静默失效**。
  （**静态对照，未实测**；要补齐的话就是一个 `ApplicationRunner` 里 ping 一次 Redis，失败即抛。）

---

### 2.3 `OrbitUserDetailsService` —— 两种装载方式，一段查库逻辑（60 行）

文件：[OrbitUserDetailsService.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/OrbitUserDetailsService.java)

**它解决什么**：`UserDetailsService` 是给 `DaoAuthenticationProvider`（**登录时**）用的；
`loadById(long)` 是给 **JWT 过滤器**（每次请求）用的。
两者共用 `OrbitUserPrincipal.from(user)`，**避免"登录时查一套、校验 token 时查另一套"而逐渐漂移**。

**`UserDetails loadUserByUsername(String username)`**（第 31–44 行）

| 顺序 | 代码 | 说明 |
|---|---|---|
| 1 | `username == null → throw new UsernameNotFoundException("用户名不能为空")` | 防御。⚠ Spring 6 的接口签名不再声明 `throws UsernameNotFoundException`，但抛它仍然有效（是 `AuthenticationException` 的兄弟，被 provider 转成 `BadCredentialsException`） |
| 2 | `userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username.trim()))` | 按用户名查。⚠ **`trim()` 是全 ASCII 的 `trim`，不是 `strip`**，而 Python 是 `User.username == data.username.strip()`（Unicode）。**登录路径上不会出问题**（`AuthController` 已经先 `.strip()` 过一次），但换个调用点就可能出现「用户名末尾是全角空格」这类差异。**改这行之前先想清楚** |
| 3 | `user == null → throw new UsernameNotFoundException("用户不存在: " + username)` | ⚠ 消息里**带上了用户名**。注释解释了为什么安全：登录接口把一切失败统一成「用户名或密码错误」，**这句不会外泄**；但日志里要能区分"查无此人"和"口令错" |
| 4 | `OrbitUserPrincipal.from(user)` | 转成主体。⚠ **注意这里没有过滤 `is_active`** —— 停用判断交给 `isEnabled()`（`DaoAuthenticationProvider` 会因此抛 `DisabledException`），这样"停用"与"口令错"在登录接口里仍然被合并成同一条 401 |

**`OrbitUserPrincipal loadById(long userId)`**（第 53–59 行）—— JWT 过滤器每次请求都走这里

```java
User user = userMapper.selectById(userId);
if (user == null || !user.active()) {
    throw new UsernameNotFoundException("账号不存在或已停用: " + userId);
}
return OrbitUserPrincipal.from(user);
```

- ⚠ **`user == null || !user.active()` 这个"或"就是"账号被删/被停用后 token 立即失效"的实现。**
  对应 Python `get_current_user` 里的 `.filter(User.id == payload["uid"], User.is_active.is_(True))`
  与 `detail="账号不存在或停用"`。
- **为什么每个请求都要查库**（性能上像浪费）：见类注释 —— 否则"用户被停用/删除后 token 还能用"。
  Python 侧也是这样（每请求一次查询）。**这是一条刻意保留的行为，不是疏忽**。
- ⚠ 抛的是 `UsernameNotFoundException`（`AuthenticationException` 的子类），
  被 `JwtAuthenticationFilter` 捕获并翻译成 `AuthFailure.UNKNOWN_USER`（§3.2）。**三层之间的这个约定要记住**。

---

### 2.4 `OrbitUserPrincipal` —— 认证主体（161 行）

文件：[OrbitUserPrincipal.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/OrbitUserPrincipal.java)

**为什么不直接用 Spring 自带的 `org.springframework.security.core.userdetails.User`**：
它只有 `username` / `password` / `authorities`，装不下这三样业务必需的东西：

| 字段 | 为什么必需 |
|---|---|
| `organizationId` | **租户隔离的依据**。每个查询都要用它过滤（Python 侧是手工 `filter(User.organization_id == user.organization_id)`） |
| `displayName` | 排课状态要显示「张三正在排课」 |
| `mustChangePassword` | 前端据此强制弹改密框 |

否则**每个 Controller 都要再查一次库**才知道当前用户属于哪个组织 —— 那等于把认证又做了一遍。

#### 逐成员

**9 个 `final` 字段 + 全参构造器**（第 34–56 行）：不可变值对象。
⚠ 与 `User` 实体的区别在于：这是"认证上下文里的一份快照"，**不做写操作**。

**`static OrbitUserPrincipal from(User user)`**（第 58–69 行）：实体 → 主体。

| 映射细节 | 说明 |
|---|---|
| `user.active()` → `active` | ⚠ 用的是实体的 `active()`（`Boolean.TRUE.equals(isActive)`），不是 `getIsActive()`。**`is_active` 为 null 时算停用** |
| `Boolean.TRUE.equals(user.getMustChangePassword())` | ⚠ **同样的防御**：列是 `NOT NULL`，但包装类型字段仍可能是 null；null → `false` |
| `user.getPasswordHash()` | 原样带进主体（见下面的 ⚠） |

⚠ **口令哈希留在这里是故意的**：`DaoAuthenticationProvider` 需要 `getPassword()` 才能比对。
它只在**单次请求的安全上下文**里存活，**不进任何响应体** —— 对外的视图是
[UserResponse](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserResponse.java)，
那个类里没有口令字段。

**业务读取方法**（第 73–91 行）：`userId()` / `organizationId()` / `displayName()` / `role()` /
`mustChangePassword()` —— 只有 getter，没有 setter。
⚠ **这几个方法名不带 `get` 前缀是有意的**（record 风格），它们**不会被 Jackson 序列化**
（`OrbitUserPrincipal` 从不作为响应体）。

**`boolean isAdmin()`**（第 94–96 行）
```java
return "admin".equals(role) || "platform_admin".equals(role);
```
对应 Python `require_admin`（[auth.py](../backend/auth.py) 第 92–95 行）：
`if user.role not in {"admin", "platform_admin"}`。**注意 `platform_admin` 也算"管理员"。**

**`boolean isPlatformAdmin()`**（第 99–101 行）：只认 `platform_admin`。
对应 Python `require_platform_admin`（第 98–101 行）。

**`UserResponse toResponse(String organizationName)`**（第 104–107 行）
把主体转成对外视图，`organizationName` 由调用方补（要再查一次 `organizations` 表）。
⚠ 这里**只传名字而不是传 `Organization`**：主体层不该依赖组织实体。

**`Collection<? extends GrantedAuthority> getAuthorities()`**（第 118–121 行）
```java
return List.of(new SimpleGrantedAuthority("ROLE_" + String.valueOf(role).toUpperCase()));
```
- Spring 的约定：`hasRole("ADMIN")` 会自动加 `ROLE_` 前缀去比，所以这里要**手写** `ROLE_` 前缀。
- ⚠ `String.valueOf(role)` 是**防 null**：`role` 为 null 时得到 `ROLE_NULL` 而不是 NPE。
- ⚠ `toUpperCase()` **没给 Locale**（对比 `LoginAttemptGuard` 用了 `Locale.ROOT`）——
  在土耳其语默认 locale 下 `"admin"` 里的 `i` 会变成 `İ`（U+0130），得到 `ROLE_ADMİN`。
  **本项目不用 `hasRole` 做判断**（见下），所以现在没有实际影响；
  但这是与 §2.2 那处 `Locale.ROOT` 不一致的地方，值得知道。

**`getPassword()` / `getUsername()`**（第 123–131 行）：`UserDetails` 的必需实现。

**`isAccountNonExpired` / `isAccountNonLocked` / `isCredentialsNonExpired`**（第 133–146 行）：
**全部 `return true`**。本项目没有"账号过期""锁定""口令过期"三种状态，
Python 侧也没有 —— 所以不要在这里塞业务（塞了就会多出 Python 没有的行为）。

**`boolean isEnabled()`**（第 157–160 行）：`return active;`
- ⚠ **这是"停用账号不能登录"的实现点**：返回 false → `DaoAuthenticationProvider` 抛 `DisabledException`
  → `AuthController` 把它和其他认证失败**统一**成 `401 用户名或密码错误`。
- **为什么不单独报"账号已停用"**：那样等于告诉攻击者"这个用户名是存在的"。
  Python 那段也是同一个分支（`if not user or not user.is_active or not verify_password:`）。

> ⚠ **权限判断走方法，不走 URL 规则**：注释里写明"本项目的权限判断**主要**走 `isAdmin()` 方法"，
> 理由在 [OrganizationController](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/OrganizationController.java)
> 的注释里说得最清楚：用 `hasRole` 表达时 403 只能给一句**通用**文案，
> 而 Python 为每种权限检查准备了**不同的中文提示**，前端会直接显示它。**文案是契约的一部分**。

---

### 2.5 `AuthController` —— `/api/auth/*` 3 个接口（179 行）

文件：[AuthController.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/AuthController.java)
Python 对应：[routers/auth.py](../backend/routers/auth.py)（48 行）

**6 个依赖**（第 47–59 行）：`AuthenticationManager` / `JwtService` / `LoginAttemptGuard` /
`UserMapper` / `OrganizationMapper` / `PasswordEncoder`。
⚠ **Controller 直接注入了两个 Mapper**（`userMapper` / `organizationMapper`）——
这在本项目里是**少见**的（其他地方都是 Controller → Service → Mapper）。
它出现在这里是因为这三个接口"薄"到不值得再包一层 Service。
**读的时候不要以为本项目到处都这样。**

#### 2.5.1 `LoginResponse login(LoginRequest, HttpServletRequest)`（第 78–106 行）—— 登录全链

按代码顺序逐行：

| # | 代码 | 在做什么 / 为什么 |
|---|---|---|
| 1 | `String username = request.username() == null ? "" : request.username();` | ⚠ 防护性归一。正常路径下 `@NotNull` 已拦住 null（422），这里只是不让 null 走到 Redis key 拼接里 |
| 2 | `String password = ... == null ? "" : ...` | 同上。**空口令不会短路**，而是照常走认证 → 失败 → 401（与 Python 一致：`verify_password("", hash)` 返回 false） |
| 3 | `String clientIp = clientIp(httpRequest);` | 见 §2.5.4 |
| 4 | `int retryAfter = loginAttemptGuard.retryAfterSeconds(username, clientIp);` | **先查限流，再验口令** —— 与 Python 顺序一致（`login_retry_after` 在第 24 行校验之前）。⚠ 传的是**原始** `username`（没 strip），与 Python 一致；strip/lower 在 `key()` 内部做，所以 `" Admin "` 与 `"admin"` 共享计数器 |
| 5 | `if (retryAfter > 0) throw BusinessException.tooManyRequests("登录失败次数过多，请在 " + retryAfter + " 秒后重试", retryAfter);` | **`Retry-After` 的完整链路在这里收口**：<br>`LoginAttemptGuard.retryAfterSeconds`（算出 N）<br>→ `BusinessException.tooManyRequests(detail, N)`（429 + 记住 N）<br>→ `ApiExceptionHandler.handleBusiness`（`headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(N))`）<br>→ 响应 `429` + `Retry-After: N` + `{"detail":"登录失败次数过多，请在 N 秒后重试"}`。<br>⚠ **文案里的 N 与响应头的 N 必须是同一个数** —— 所以这里用的是同一个局部变量，**不要改成再算一次**（两次调用之间 TTL 会变，就会出现"文案说 297、头里写 296"） |
| 6 | `Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username.strip(), password));` | ⚠ **`username.strip()`** —— 与 Python 的 `data.username.strip()` 对齐（Unicode 空白）。口令**不 strip**（Python 也不 strip） |
| 7 | `principal = (OrbitUserPrincipal) authentication.getPrincipal();` | provider 返回的 principal 就是 `OrbitUserDetailsService` 给的那个对象 |
| 8 | `catch (AuthenticationException e)` → `log.debug("登录失败 user={} ip={} reason={}", ...)` | ⚠ **只记 debug**：登录失败是**正常业务事件**，不是系统故障（记成 error 会把日志淹掉）。<br>⚠ **也绝不记口令 —— 连长度都不记**（注释原话） |
| 9 | → `loginAttemptGuard.recordFailure(username, clientIp);` → `throw BusinessException.unauthorized("用户名或密码错误");` | **失败计数在这里 +1**。⚠ 用的是**原始** username（Python 也一样），key 内部再归一 |
| 10 | `loginAttemptGuard.clear(username, clientIp);` | **成功才清零**。⚠ 位置在第 104 行 —— **在异常分支之外**，所以只有真成功才走到 |
| 11 | `return LoginResponse.of(jwtService.issue(principal.userId()), toResponse(principal));` | **签发**：`JwtService.issue(userId)` → 标准 JWT（`sub`=userId、`jti`=随机 UUID 对应 Python 的 `nonce`、`iat`/`exp`=现在/现在+ttl）。<br>⚠ **与 Python 的 token 格式不通用（有意）**，理由见[第 1 篇](代码导读.md) §1.9：token 对前端不透明 ⇒ 格式不属于契约；代价是灰度切换时**老 token 失效、用户要重新登录一次** |

**为什么把所有 `AuthenticationException` 合并成一条消息**（类注释第 72–76 行）：
Spring 会因为「用户不存在」（`BadCredentialsException`）与「账号停用」（`DisabledException`）抛不同异常。
分开报告**等于告诉攻击者"这个用户名是存在的"** —— Python 那边刻意合并成一个分支，这里保持一致。

#### 2.5.2 `UserResponse me(OrbitUserPrincipal)`（第 109–112 行）

```java
return toResponse(requirePrincipal(principal));
```
- 对应 Python `GET /api/auth/me`（`user: User = Depends(get_current_user)` → `return user`）。
- ⚠ **`@AuthenticationPrincipal` 在这里为什么可能为 null**：对象是从 `SecurityContext` 里取的；
  正常路径上授权层已保证"已认证"，但在异常场景下它确实可能是 null。
  `requirePrincipal` 把它变成 `401 请先登录`，**而不是 NPE → 500**。

#### 2.5.3 `Map<String, Object> changePassword(PasswordChangeRequest, OrbitUserPrincipal)`（第 121–148 行）

| # | 代码 | 在做什么 / 为什么 |
|---|---|---|
| 1 | `OrbitUserPrincipal current = requirePrincipal(principal);` | null → 401 请先登录 |
| 2 | `User user = userMapper.selectById(current.userId());` | **重新查库拿实体**（主体是快照，不能直接改） |
| 3 | `if (user == null \|\| !user.active()) throw BusinessException.unauthorized("账号不存在或已停用");` | ⚠ **这一句在 Python 的 `change_password` 里没有**（Python 的用户来自 `get_current_user`，那里已经过滤过 `is_active`）。<br>在 Java 里过滤器也已经过滤过一次，所以这里是**第二道**。多一道不会产生行为差异（同样的输入仍然 401、同一句文案），**但要知道它为什么存在**：以防 `changePassword` 在别的入口被调用时漏掉这道检查 |
| 4 | `if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) throw BusinessException.badRequest("当前密码不正确");` | **400**（不是 401），对应 Python `HTTPException(400, "当前密码不正确")` |
| 5 | `try { encoded = passwordEncoder.encode(request.newPassword()); } catch (IllegalArgumentException e) { throw BusinessException.badRequest(e.getMessage()); }` | ⚠ **这就是"新密码至少 8 位 → 400"的实现方式**：编码器抛 `IllegalArgumentException("密码至少需要 8 位")`，这里原样转成 400，文案逐字来自异常消息。对应 Python 的 `except ValueError as exc → HTTPException(400, str(exc))`。<br>⚠ **如果图省事在 DTO 上写 `@Size(min = 8)`，会变成 422，状态码就不一样了** —— 类注释专门写了这句 |
| 6 | `User patch = new User(); patch.setId(user.getId()); patch.setPasswordHash(encoded); patch.setMustChangePassword(false); userMapper.updateById(patch);` | ⚠ **为什么 new 一个只有两个字段的实体**：MyBatis-Plus 的 `updateById` **默认忽略 null 字段**，所以这等价于 `UPDATE users SET password_hash=?, must_change_password=0 WHERE id=?`。<br>⚠ 但**有一个后果**：如果直接改第 2 步查出来的 `user` 再 `updateById(user)`，会把**所有**字段写回去（含 `is_active`、`role`）—— 那是更宽的写，容易在并发下覆盖别人的修改。**这里的选择是"只写两列"，更安全**<br>⚠ **这里没有重新读库**：所以实体上的 `createdAt` 等字段没变，但反正不返回实体 |
| 7 | `return Map.of("ok", true);` | 对应 Python `return {"ok": True}`。⚠ 用 `Map.of` 而不是写一个 DTO：这个响应只有一个键，没有复用价值 |

⚠ **`mustChangePassword=false` 这一句是必须的**：改完密码后前端的强制改密弹窗要消失。
Python 是 `user.must_change_password = False`。**漏了它不会报错**，只会在下次登录时又弹一次改密框。

#### 2.5.4 三个私有工具方法（第 150–178 行）

**`private UserResponse toResponse(OrbitUserPrincipal principal)`**
```java
Organization organization = organizationMapper.selectById(principal.organizationId());
return principal.toResponse(organization == null ? null : organization.getName());
```
- ⚠ **每个 `/api/auth/me` 都会多一次 `organizations` 查询**。
  Python 是 `UserOut.model_validate(user)` 走 SQLAlchemy 的 `organization` 关系（**懒加载，也是多一次查询**）。
  两边都是"两次查询"，行为一致。
- ⚠ **组织查不到时给 `null` 而不是报错**：`organization_name` 是 `Optional[str]`，
  而且 `json.default-property-inclusion: always` 会把它**输出成 `null`**（不是省略）。

**`private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal)`**
```java
if (principal == null) throw BusinessException.unauthorized("请先登录");
```
- 注释写明理由：正常到不了这里，但 `@AuthenticationPrincipal` 在异常场景下**确实可能为 null**，
  那时应该给 401 而不是 NPE → 500。

**`private static String clientIp(HttpServletRequest request)`**
```java
String remote = request.getRemoteAddr();
return remote == null ? "unknown" : remote;
```
- ⚠ **与 Python 对齐用的就是直连地址**：Python 是 `request.client.host if request.client else "unknown"`
  （`routers/auth.py` 第 15 行）—— **连兜底值 `"unknown"` 都同**。
- ⚠ **TODO(P3) 已写在注释里**：上网关之后所有请求的 `remoteAddr` 都会变成**网关地址**，
  那时必须改成读 `X-Forwarded-For`（**并且只信网关写入的那一段** —— 否则客户端可以伪造 IP 绕过限流）。

---

### 2.6 三个 DTO

#### `LoginRequest`（27 行）
[LoginRequest.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/LoginRequest.java)

```java
public record LoginRequest(
        @NotNull(message = "Field required") String username,
        @NotNull(message = "Field required") String password) {}
```

⚠⚠ **用 `@NotNull` 而不是 `@NotBlank`，这不是笔误**（类注释专门写了）。对照 Python
`class LoginRequest(BaseModel): username: str` —— 「必填、但允许空字符串」：

| 请求体 | Python | 这里 | 状态码 |
|---|---|---|---|
| 没有 `username` 字段 | Pydantic `Field required` | `@NotNull` 触发 | **422** |
| `"username": ""` | 通过校验 → 查库查不到 → 401 | 同（`@NotNull` 不拦空串） | **401 用户名或密码错误** |

**用 `@NotBlank` 会把第二种情况也变成 422，与 Python 不一致。**
（实测过：`{"detail":[{"type":"missing","loc":["body","name"],...}]}`；
契约工具里也有 `auth/login 缺字段 → 422` 这条用例。）

#### `LoginResponse`（21 行）
[LoginResponse.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/LoginResponse.java)

```java
public record LoginResponse(String accessToken, String tokenType, UserResponse user) {
    public static LoginResponse of(String accessToken, UserResponse user) {
        return new LoginResponse(accessToken, "bearer", user);
    }
}
```
- 对应 Python `{"access_token": ..., "token_type": "bearer", "user": UserOut...}`。
- ⚠ **字段名靠全局 `SNAKE_CASE` 自动变成 `access_token` / `token_type`** ——
  所以这里写 camelCase 是**对的**，**不要手工贴 `@JsonProperty`**（贴了会与全局策略打架）。

#### `PasswordChangeRequest`（21 行）
[PasswordChangeRequest.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/PasswordChangeRequest.java)

```java
public record PasswordChangeRequest(
        @NotNull(message = "Field required") String currentPassword,
        @NotNull(message = "Field required") String newPassword) {}
```
- 两个字段都是「必填但无长度约束」→ 同 `LoginRequest` 的理由，用 `@NotNull`。
- ⚠ **「新密码至少 8 位」刻意不在这里**（否则 422），由编码器抛异常 → 400（见 §2.5.3 第 5 步）。

---

### 2.7 `PythonPbkdf2PasswordEncoderTest` —— 为什么这一段最不能出错（127 行）

文件：[PythonPbkdf2PasswordEncoderTest.java](../backend-java/orbit-academic/src/test/java/com/orbit/academic/auth/PythonPbkdf2PasswordEncoderTest.java)

**它保护的东西**：`users.password_hash` 里已经有 Python 生成的哈希。
**Java 校验不了它们 ⇒ 迁移后所有老用户都登录不了。**
所以测试里的向量是**用 Python 侧真实代码算出来的**，不是手写猜的
（类注释里写明了：`ITERATIONS = 310000`、口令 `Admin@123`、固定盐
`00112233445566778899aabbccddeeff`、摘要 `b847df49…ee3b`）。

| 测试方法 | 断言什么 | 为什么值得单独一条 |
|---|---|---|
| `verifiesPythonGeneratedHash` | 两条 Python 真实哈希（固定盐 + 随机盐）都能校验通过 | **这是整篇最重要的一条断言** |
| `rejectsWrongPassword` | 错误口令 / 差一位 / 空串 / **null** 都 false | null 走的是"不抛异常"分支 |
| `producesCompatibleFormat` | 自产哈希是 4 段、算法名 `pbkdf2_sha256`、轮数 `310000`、盐 32 hex、摘要 64 hex，且能自校验 | 锁住**格式**（Python 侧要能读） |
| `saltIsRandom` | 同一口令两次 encode 结果**不相等** | 盐必须随机，否则相同口令产生相同哈希 |
| `digestMatchesPython` | 用 Python 那条向量的盐与轮数，`digestHex` 算出的 hex **逐字符相同** | 这条直接证明"算法实现对得上"，不是"格式看着对" |
| `rejectsShortPassword` | `encode("short")` → `IllegalArgumentException("密码至少需要 8 位")` | **文案也是契约** |
| `malformedHashReturnsFalse` | `not-a-hash` / `""` / 轮数不是数字 / 算法是 bcrypt / 盐不是合法 hex → **全部 false，不抛** | "一个坏行不能让整个登录接口 500" |
| `supportsNonAsciiPassword` | 中文口令能 encode + matches | ⚠ **`@DisplayName` 里写了 emoji，方法体里没有 emoji**（见 §2.1 ⚠ ③） |

---

## 3. `JwtAuthenticationFilter` —— 把 token 变成安全上下文（101 行）

文件：[JwtAuthenticationFilter.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/auth/JwtAuthenticationFilter.java)
共享的文案常量：[AuthFailure.java](../backend-java/orbit-common/src/main/java/com/orbit/common/security/AuthFailure.java)（第 1 篇 §1.10）

### 3.1 它在安全链里的位置与职责边界

```
请求 → [ JwtAuthenticationFilter ] → 授权层(authorizeHttpRequests) → Controller
              ↑                              ↓ 未认证
        只做"变成已认证"             AuthenticationEntryPoint 写 401
```

**⚠ 过滤器不自己写响应** —— 这是本篇最容易被误改的一处。两条理由（都写在类注释里）：

1. **有些接口必须无认证可达**（健康检查、登录）。如果过滤器自己看到没 token 就写 401，
   那两个接口**全都会被挡掉**。
2. **放行与否是授权层的职责**：过滤器只准备上下文，`authenticationEntryPoint` 统一写 401。

**但它必须把"为什么失败"记下来** —— 因为 Python 的 401 文案分三种（见 §3.3）。

### 3.2 逐行

**`protected void doFilterInternal(request, response, filterChain)`**（第 53–73 行）

| # | 代码 | 在做什么 |
|---|---|---|
| 1 | `if (SecurityContextHolder.getContext().getAuthentication() == null) { … }` | **已经有认证就不再覆盖**（例如测试里手工设置的上下文） |
| 2 | `String token = bearerToken(request);` | 从 header 取（见 §3.4） |
| 3 | `if (token == null) request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.NO_TOKEN);` | 写请求属性 `com.orbit.auth.failure = "请先登录"`。⚠ **不 return、不写响应**，继续往下走 |
| 4 | `Optional<Long> userId = jwtService.userIdOf(token);` | 校验签名 / 过期 / 格式（`JwtService` 内部失败一律 `Optional.empty()`，**不抛异常**） |
| 5 | `if (userId.isEmpty()) request.setAttribute(..., AuthFailure.INVALID_TOKEN);` | 文案 `登录已失效，请重新登录` |
| 6 | `else authenticate(request, userId.get());` | 见下 |
| 7 | `filterChain.doFilter(request, response);` | **无论哪条分支都继续走链**（是否放行由授权层决定） |

⚠ **三种原因的对应关系（就是 Python `auth.py` 的三条分支）**：

| Python 位置 | 情况 | 请求属性值 |
|---|---|---|
| `if not credentials or credentials.scheme.lower() != "bearer"` → `"请先登录"` | 没带 / 不是 Bearer | `AuthFailure.NO_TOKEN` |
| `decode_token` 抛 401 `"登录已失效，请重新登录"` | 签名不对 / 过期 / 格式非法 | `AuthFailure.INVALID_TOKEN` |
| `if not user:` → `"账号不存在或停用"` | token 有效但用户没了 / 停用 | `AuthFailure.UNKNOWN_USER` |

> ⚠ **这三条最初只做了一条**（都回"请先登录"），靠影子对比工具比对多次请求才发现
> —— 详情见[第 1 篇](代码导读.md) §1.10 与 `AuthFailure` 的注释。**过期是 8 小时就会遇到的常见情况**，
> 前端直接把这句话显示给用户，所以它值得做对。

**`private void authenticate(HttpServletRequest request, long userId)`**（第 75–91 行）

```java
try {
    OrbitUserPrincipal principal = userDetailsService.loadById(userId);   // ← 每请求查库
    UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
} catch (UsernameNotFoundException e) {
    request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.UNKNOWN_USER);
    SecurityContextHolder.clearContext();
}
```

| 细节 | 为什么 |
|---|---|
| **第 2 个参数是 `null`**（credentials） | 已经认证过了，不需要再带口令。⚠ 传 `principal` 当 credentials 会让口令哈希进入 `Authentication` 对象更深的生命周期 |
| `setDetails(new WebAuthenticationDetailsSource().buildDetails(request))` | 标准做法：把 `remoteAddress` / `sessionId` 挂到认证对象上（审计/日志会用到）。**没有它，`Authentication.getDetails()` 是 null** |
| `catch (UsernameNotFoundException)` → **`clearContext()`** | ⚠ **`clearContext()` 不能省**：`loadById` 抛异常前理论上没写上下文，但**显式清一次**是"保证这个请求保持未认证"的更强保证（避免线程复用/嵌套调用留下的脏状态） |
| **异常只 catch 这一个** | `loadById` 只抛这一种。其它异常（比如数据库连不上）**要让它冒出去变 500** —— 把"数据库故障"错报成 401 会让人往错方向排查（这与 `K3` 那条踩坑同一个主题） |

⚠ **为什么"每个请求都查一次库"是可接受的**：见 §2.3 —— 这是为了对齐 Python 的
`get_current_user`（它同样每请求过滤 `is_active`），以及"停用后 token 立即失效"这条行为。

**`private static String bearerToken(HttpServletRequest request)`**（第 93–100 行）

```java
String header = request.getHeader("Authorization");
if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
    return null;
}
String token = header.substring(BEARER_PREFIX.length()).trim();
return token.isEmpty() ? null : token;
```

| 细节 | 为什么 |
|---|---|
| `BEARER_PREFIX = "Bearer "`（**带尾空格**） | 前缀里含空格，所以判断的是"`Bearer ` + 内容" |
| `regionMatches(true, 0, …)` | **忽略大小写** → `bearer `/`BEARER ` 都收。对应 Python `credentials.scheme.lower() != "bearer"` |
| `.trim()` | 去掉 `Bearer    token` 的多个空格 |
| `token.isEmpty() → null` | `"Bearer "` 后面什么都没有 → 当作"没带 token" → 文案是**请先登录**（而不是"登录已失效"）。⚠ 与 Python 的差别：FastAPI 的 `HTTPBearer` 在这种输入下会给出什么要看它的实现，**这一点本篇没有对照验证**（代码行为是：空 token → `NO_TOKEN`） |

### 3.3 ⚠ 为什么**不注册成 `@Bean`**（第 33–38 行注释，第 97–100 行注释）

Spring Boot 会把容器里**所有的 `Filter` Bean 自动注册到 Servlet 容器**上。
于是它会**同时**作为普通 Filter 和作为安全链的一环被执行。
虽然 `OncePerRequestFilter` 能防止同一请求内重复执行，但它在**安全链之外**也会跑一遍，
**时序会变得难以推理**。
所以：**由 `SecurityConfig` 直接 `new`**，只挂在安全链上（§4.3）。

---

## 4. `SecurityConfig` —— 认证与授权的装配（150 行）

文件：[SecurityConfig.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/config/SecurityConfig.java)

**它没有照搬 Python 的「手写 `verify_password` + 每个接口 `Depends(get_current_user)`」**，
因为在 Spring 里那等于把框架已经做好的事重做一遍。
但**两件事必须与 Python 一致（它们是契约，不是实现）**（类注释第 42–50 行）：

1. **口令格式**：`PasswordEncoder` 必须是 `PythonPbkdf2PasswordEncoder`（否则老用户登录不了）。
2. **未认证返回 401（不是 403）**，且文案**按失败原因分三种**。
   ⚠ 前端 axios 拦截器**只认 401 触发登出**；Spring Security 默认给 403，
   用户会卡在「没登录却退不出去」（这是[踩坑总结](踩坑总结.md) C3 那条）。

### 4.1 `@Bean PasswordEncoder passwordEncoder()`（第 69–72 行）

```java
return new PythonPbkdf2PasswordEncoder();
```
唯一实现，没有条件分支。**换成别的编码器 = 迁移后所有老用户登录不了。**

### 4.2 `@Bean AuthenticationManager authenticationManager(...)`（第 82–90 行）

```java
DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
provider.setPasswordEncoder(passwordEncoder);
provider.setHideUserNotFoundExceptions(true);
return new ProviderManager(provider);
```

| 细节 | 为什么 |
|---|---|
| ⚠ `new DaoAuthenticationProvider(userDetailsService)` | **构造器注入 `UserDetailsService`**。比老的 `setUserDetailsService(...)` 好：Spring 6 起 `setUserDetailsService` 已被**弃用** |
| `setHideUserNotFoundExceptions(true)` | 隐藏"用户名不存在"与"口令错误"的区别。⚠ **默认就是 true**，这里**显式写出意图**（注释原话）—— 读代码的人不用去查默认值 |
| `new ProviderManager(provider)` | 只装一个 provider。⚠ Spring Boot 会自动配一个 `AuthenticationManager`，但**这里显式定义**，因为登录流程要用我们自己的编码器与用户服务 |
| 停用账号怎么走到这里 | `OrbitUserPrincipal.isEnabled() == false` → provider 抛 `DisabledException`（也是 `AuthenticationException`） |

### 4.3 `@Bean SecurityFilterChain orbitSecurityFilterChain(...)`（第 92–124 行）—— 放行清单

```java
JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userDetailsService);

http
    .csrf(AbstractHttpConfigurer::disable)
    .httpBasic(AbstractHttpConfigurer::disable)
    .formLogin(AbstractHttpConfigurer::disable)
    .logout(AbstractHttpConfigurer::disable)
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/health",
                    "/actuator/health", "/actuator/health/**",
                    "/actuator/info", "/error").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .anyRequest().authenticated())
    .exceptionHandling(handling -> handling
            .authenticationEntryPoint(SecurityConfig::writeUnauthorized)
            .accessDeniedHandler(SecurityConfig::writeForbidden))
    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
return http.build();
```

**逐项解释**（顺序即代码顺序）：

| # | 配置 | 为什么 |
|---|---|---|
| 1 | `csrf(...disable)` | 纯 JWT API，没有 cookie/session，**没有 CSRF 面**。⚠ 开着会让所有 POST/PUT 403 |
| 2 | `httpBasic(...disable)` / `formLogin(...disable)` / `logout(...disable)` | 这三个默认形态都会：① 返回 `WWW-Authenticate` 头或 HTML 登录页（**不是 JSON 契约**）；② 建 session。**全部关掉** |
| 3 | `sessionCreationPolicy(STATELESS)` | 无 session（token 是无状态凭证）。⚠ 不设的话 Spring 可能创建 `JSESSIONID`，而 Python 侧没有 |
| 4 | **放行清单**（见下表） | |
| 5 | `.anyRequest().authenticated()` | ⚠ **其余一律要认证** —— 包括 `/api/auth/me` 与 `/api/auth/change-password`。**本项目的"授权"（谁能调）不在这个文件里**，而是 Controller 里的显式检查（§5.6 / §6.6） |
| 6 | `authenticationEntryPoint(writeUnauthorized)` | **未认证**（401）的出口 |
| 7 | `accessDeniedHandler(writeForbidden)` | **已认证但权限不够**（403）的安全网，见 §4.5 |
| 8 | `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)` | 放在标准用户名口令过滤器**之前**。⚠ 本项目不用那个过滤器（没有表单登录），这个位置只是"链靠前"的稳定锚点 |

**放行清单（每一行为什么必须放行）**：

| 路径 | 方法 | 为什么放行 |
|---|---|---|
| `/api/health` | 任意 | **探针/负载均衡必须无认证可达**，否则探不到健康状态、实例被摘掉（[踩坑总结](踩坑总结.md) C3 的原话） |
| `/actuator/health`、`/actuator/health/**` | 任意 | 同上（Spring Actuator 的路径；`/**` 覆盖 liveness/readiness 子路径） |
| `/actuator/info` | 任意 | 版本信息，同上 |
| `/error` | 任意 | ⚠ **转发路径**。不放行的话，容器内部的错误转发会被再次拦成 401，**真正的 500/404 就看不见了** |
| `/api/auth/login` | **仅 POST** | 登录本身当然要匿名可达 |

⚠ **注意 `HttpMethod.POST` 这个限定**：`GET /api/auth/login` 会落到 `anyRequest().authenticated()`
→ **未带 token 时返回 401**，而不是 405（Method Not Allowed）。
Python（FastAPI）对同一条路径的错误方法会给 **405**。
**（这是从匹配规则静态推断的，本篇没有实测这个请求；如果要确认，加一条用例打 `GET /api/auth/login` 看两边状态码。）**
真正的 405 只有在**带着有效 token** 时才会由 `ApiExceptionHandler` 产生。

### 4.4 `writeUnauthorized` —— 401 文案的出口（第 126–134 行）

```java
Object reason = request.getAttribute(AuthFailure.ATTRIBUTE);
String detail = reason instanceof String text ? text : AuthFailure.NO_TOKEN;
writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, detail);
```

| 细节 | 为什么 |
|---|---|
| 读 `AuthFailure.ATTRIBUTE` | 过滤器写的原因（§3.2）。**这就是"职责分离 + 文案逐字对齐"的接口点** |
| 兜底 `AuthFailure.NO_TOKEN` | 属性不在（比如授权层直接拦下、过滤器没跑）→ 按"没带 token"处理 |
| `reason instanceof String text`（模式匹配） | ⚠ 属性是任意 `Object`，**先判类型再取**，避免 `(String)` 强转时的 `ClassCastException` |
| 状态码 `401` 硬编码 | 与前端 axios 拦截器的约定 |

### 4.5 `writeForbidden` 与 `writeJson`（第 136–149 行）

**`writeForbidden`**
```java
writeJson(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_DETAIL);  // "没有执行此操作的权限"
```
⚠ **正常路径上不应该触发**：本项目的角色判断是**显式写在代码里的**
（`principal.isAdmin()` / `isPlatformAdmin()`），这样才能给出 Python 那句具体的
`仅管理员可以执行此操作`。这里只是防止有人用 `hasRole(...)` 写了规则却忘了配 handler，
而拿到 Spring 默认的 **HTML 错误页**。
⚠ 所以 `FORBIDDEN_DETAIL = "没有执行此操作的权限"`（第 67 行）与
`UserController.ADMIN_ONLY = "仅管理员可以执行此操作"`、`OrganizationController.PLATFORM_ADMIN_ONLY`
是**三句不同的文案**，各有各的出口。

**`writeJson`**
```java
response.setStatus(status);
response.setContentType("application/json;charset=UTF-8");
response.getWriter().write(JSON.writeValueAsString(Map.of("detail", detail)));
```
- ⚠ **用 Jackson 拼（而不是字符串相加）**：文案里若有引号/反斜杠也不会破坏 JSON。
- ⚠ `charset=UTF-8` 不能省：中文文案在默认字符集下会乱码。
- ⚠ **这两个 handler 是绕过 `@RestControllerAdvice` 的**（它们在过滤器/安全链层面执行，
  还没进 DispatcherServlet）—— 所以**错误体形状要在这里手工保持与 `ErrorResponse` 一致**：
  就是 `{"detail": "<字符串>"}`。改 `ErrorResponse` 时要同时想到这里（scheduling 侧有一份**同样**的实现）。

### 4.6 ⚠ 跨模块说明：`/internal/**` 的 `permitAll` 是 **scheduling** 的代码

本节不属于 `orbit-academic`，但被问到过，所以在这里讲清楚**理由**，并标明位置。

位置：[SchedulingSecurityConfig.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/config/SchedulingSecurityConfig.java)
第 68 行：

```java
// P3：worker 的内部接口。它们**不能**要求用户 JWT（worker 没有用户身份），
// 所以在这里放行、由 InternalJobController 自己校验共享密钥
// X-Worker-Secret。生产环境还应该在网络层限制（只有 worker 能访问），
// 那是 P3 网关的职责。
.requestMatchers("/internal/**").permitAll()
```

**为什么敢放行**：worker 是**没有用户身份**的进程（它不登录、没有 token），
`/internal/jobs/claim`、`/heartbeat`、`/result` 这些接口必须是"机器对机器"调用。
所以授权层放行，**由控制器自己校验共享密钥 `X-Worker-Secret`**。

**代价（要记住的）**：这是"**授权层不设防、把校验下移到控制器**"的写法 ——
任何新增 `/internal/**` 接口**都必须自己校验密钥**，漏了就是**无认证可写**。
`orbit-academic` **没有**这类接口，所以它的 `SecurityConfig` 里也没有这条规则。

---

## 5. 组织 / 工作空间（`organization` 包）

### 5.1 `Organization` —— 租户实体（48 行）

文件：[Organization.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/Organization.java)

```java
@TableName("organizations")
public class Organization {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    private LocalDateTime createdAt;
    …getter/setter…
}
```

- `@TableName("organizations")`：显式表名。⚠ 类名是单数 `Organization`、表名是复数，
  **不写这个注解 MyBatis-Plus 会去找 `organization` 表** → 运行时报"表不存在"。
- `@TableId(type = IdType.AUTO)`：对应库里的 `AUTO_INCREMENT`。
  ⚠ 不写 `type`（默认 `ASSIGN_ID` 雪花算法）会**自己生成一个 19 位 id 去插**，
  与 `int` 列和 Python 的自增语义都不一致。
- 字段：`id` / `name` / `createdAt`（→ `created_at`，靠 `map-underscore-to-camel-case`）。
- 表约束（[V1 脚本](../backend-java/orbit-academic/src/main/resources/db/migration/academic/V1__baseline_legacy.sql) 第 35–42 行）：
  `name varchar(120) NOT NULL` + **`UNIQUE KEY name`**、`created_at datetime NOT NULL`。
  ⚠ **`name` 的唯一性是库级的** —— 所以 `OrganizationService.create` 里的 `selectCount` 预检查
  只是为了让错误变成 **409 而不是 500**；并发下仍可能撞唯一键（两边都会 500，行为一致）。
- 用普通类而不是 record：**MyBatis-Plus 需要可变 setter 来填结果**。

### 5.2 `OrganizationMapper`（29 行）—— 唯一的自定义 SQL

文件：[OrganizationMapper.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/OrganizationMapper.java)

```java
@Mapper
public interface OrganizationMapper extends BaseMapper<Organization> {

    @Select("SELECT id FROM organizations WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}
```

- **`lockById` 是行级写锁**（`SELECT … FOR UPDATE`），对应 Python 的
  `db.query(Organization).filter(Organization.id == user.organization_id).with_for_update().one()`。
- **谁调它**（不在本篇范围，但必须知道它在服务什么）：自动分配教师、批量设置教师班级这两个接口。
- ⚠ **锁的是 `organizations` 这一行（租户维度），不是具体业务表** —— 这是"用一个稳定的对象
  把租户内的操作串行化"的常见手法，意味着**这两个接口在同一学校内是串行的**（不同学校互不影响）。
- ⚠ **必须在事务里调用**，锁才在提交/回滚时自动释放。
- ⚠ **只 `SELECT id`**（不是 `SELECT *`）：只要锁，不需要数据。
- ⚠ 与别的 Mapper 不同，这里**有真的 SQL**（第 1 篇 §0.1 的约定：有 `@Select`/`@Update` 注解的都是自定义 SQL）。
- `@Mapper` 注解其实可以省（`AcademicApplication` 的 `@MapperScan` 已覆盖），
  保留是为了"这个接口是 Mapper"在**单个文件里自证**。

### 5.3 `OrganizationResponse`（19 行）

文件：[OrganizationResponse.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/OrganizationResponse.java)

```java
public record OrganizationResponse(Long id, String name, LocalDateTime createdAt) {
    public static OrganizationResponse from(Organization organization) { … }
}
```
- JSON：`{"id": 2, "name": "第二学校", "created_at": "2026-09-27T06:21:25"}`
- ⚠ **不含任何管理员信息** —— 创建组织时会同时建一个管理员用户，但响应**只返回组织本身**
  （与 Python `response_model=OrganizationOut` 一致）。
- ⚠ `createdAt` 是 `LocalDateTime`（无时区）→ 序列化成 `"2026-09-27T06:21:25"`。
  **值必须是 UTC**，见 §5.5 的 `UtcTimes.now()`。

### 5.4 `OrganizationCreateRequest`（32 行）

文件：[OrganizationCreateRequest.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/OrganizationCreateRequest.java)

```java
public record OrganizationCreateRequest(
        @NotNull(message = "Field required") String name,
        @NotNull(message = "Field required") String adminUsername,
        @NotNull(message = "Field required") String adminDisplayName,
        @NotNull(message = "Field required") String adminPassword) {}
```

⚠ **「字段缺失」与「字段是空字符串」是两条不同的路径，状态码不同**：

| 输入 | 走到哪 | 状态码 |
|---|---|---|
| 没有 `name` / `admin_username` | Jakarta `@NotNull` | **422** |
| `"name": "   "`（纯空白） | 通过校验 → Service `strip()` 后判空 | **400 工作空间名称和管理员用户名不能为空** |

契约工具里两条用例都有（`organizations 新建缺字段 → 422`、`organizations 新建空名称 → 400`）。
**两者不能合并** —— 合并了就会有一边的状态码对不上。

### 5.5 `OrganizationService`（162 行）—— 含一条"故意不做"的事

文件：[OrganizationService.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/OrganizationService.java)

**静态常量**（第 55–60 行）

```java
private static final Map<String, SFunction<Organization, ?>> SORTABLE = Map.of(
        "id", Organization::getId,
        "name", Organization::getName,
        "created_at", Organization::getCreatedAt);
private static final String DEFAULT_SORT = "id";
```
⚠ **这是排序白名单的实现方式**：`order_by` 的值 → **类型安全的列引用**（`SFunction`），
**不是字符串拼进 SQL**。白名单之外的 `order_by` 由 `PageQuery.orderColumn(...)` 抛 400。
`SORTABLE` 的 key 是**前端传的 snake_case 名字**（`created_at`），value 是方法引用。

**`PageResponse<OrganizationResponse> list(PageQuery query)`**（第 82–95 行）

| # | 代码 | 说明 |
|---|---|---|
| 1 | `LambdaQueryWrapper<Organization> wrapper = Wrappers.lambdaQuery();` | 空条件起手 |
| 2 | `if (query.hasKeyword()) wrapper.like(Organization::getName, query.keyword());` | 关键字只匹配 `name`。⚠ 用的是 **`query.keyword()`**（已经过 `PageQuery` 归一化的原始关键字），**不是 `likePattern()`** —— 因为 MyBatis-Plus 的 `like()` **自己会加 `%…%`**。⭐ **传 `likePattern()` 会变成 `%%关键字%%`**（多一层通配符，虽然结果常常一样，但语义已经错了）。对比 `UserService.list` 用的是 `query.keyword()`，两边一致 |
| 3 | `String column = query.orderColumn(SORTABLE.keySet(), DEFAULT_SORT);` | 白名单校验：不在 `{id, name, created_at}` 里 → **400** |
| 4 | `wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));` | ⚠ **第二个参数是 `isAsc`**（不是 `isDesc`）。传反了**不报错、只是方向相反**（[踩坑总结](踩坑总结.md) H3 / [第 1 篇](代码导读.md) 坑 3）。所以这里传的是 `ascending()` |
| 5 | `organizationMapper.selectPage(new Page<>(query.pageNumber(), query.pageSize()), wrapper)` | 真分页。⚠ **没有注册分页拦截器时这里会静默返回全部**（见 §8.2） |
| 6 | `PageResponse.of(records.stream().map(OrganizationResponse::from).toList(), page.getTotal())` | 包成 `{items,total}` |

⚠⚠ **这个接口天然不按租户过滤**（第 77–80 行注释）：它是平台管理员用来管理**所有**学校的，
Python 侧也是 `db.query(Organization).order_by(Organization.id).all()`。
**权限由 Controller 的平台管理员检查保证（403）。**
**换句话说：这是全项目唯一一个「跨租户读」的接口**，改动时务必留意。

**`OrganizationResponse create(OrganizationCreateRequest request)`**（第 111–157 行，`@Transactional`）

| # | 代码 | 说明 |
|---|---|---|
| 1 | `String name = strip(request.name()); String username = strip(request.adminUsername());` | 先归一（null → ""，再 `strip()`） |
| 2 | `if (name.isEmpty() \|\| username.isEmpty()) throw badRequest("工作空间名称和管理员用户名不能为空");` | **400**。⚠ 注意这是**一条**文案管两个字段（Python 也是 `if not name or not username:` 一个分支） |
| 3 | `organizationMapper.selectCount(...eq(name)) > 0 → conflict("工作空间名称已存在")` | **409** |
| 4 | `userMapper.selectCount(...eq(username)) > 0 → conflict("管理员用户名已存在")` | **409**。⚠ 顺序是**先查组织名、再查用户名** —— Python 也是这个顺序（[routers/organizations.py](../backend/routers/organizations.py) 第 23–26 行）。**两个都冲突时先报哪一个，是契约的一部分**（[踩坑总结](踩坑总结.md) H10 那条"报第一个冲突依赖遍历顺序"） |
| 5 | `try { passwordHash = passwordEncoder.encode(request.adminPassword()); } catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }` | **400 密码至少需要 8 位** |
| 6 | `organization.setCreatedAt(UtcTimes.now()); organizationMapper.insert(organization);` | ⚠ **必须显式写 `created_at`**：Python 侧是 `Column(default=utcnow)` 由 ORM 填。Java 里字段为 null 时 MyBatis-Plus 会**把 null 插进去** → `created_at datetime NOT NULL` 直接报错。<br>⚠ **必须用 `UtcTimes.now()`（UTC + 截断到秒）**，不是 `LocalDateTime.now()`（本地时间，与 Python 差 8 小时）。**不报错、不崩**，只在排序/显示时表现很怪（[第 1 篇](代码导读.md) §1.1） |
| 7 | 建管理员 `User`：`setOrganizationId(organization.getId())` | ⚠ **依赖 `insert` 之后 id 被回填**（`IdType.AUTO` + MyBatis-Plus 的 `useGeneratedKeys`）。对应 Python 的 `db.flush()`（为了拿到自增 id） |
| 8 | `admin.setDisplayName(displayName.isEmpty() ? username : displayName);` | ⚠ 逐字复刻 Python `display_name=data.admin_display_name.strip() or username`：**空白显示名回退成用户名** |
| 9 | `admin.setRole("admin"); admin.setIsActive(true); admin.setMustChangePassword(true);` | ⚠ **新管理员不是 `platform_admin`**（Python 也是 `role="admin"`），且**首次登录必须改密码** |
| 10 | `admin.setCreatedAt(UtcTimes.now()); userMapper.insert(admin);` | ⚠ **两次 `UtcTimes.now()` 是两次独立调用**，时间可能差 1 秒（组织行与用户行的 `created_at` 不完全相等）。Python 侧两个 `default=utcnow` 也是各自求值 —— **行为一致** |
| 11 | `@Transactional` | 组织 + 管理员**一个事务**。⚠ 任一步抛异常都不会留下"有组织没管理员"的半成品 |

#### ⚠⚠ 这里**故意不写** `scheduling_state`（类注释第 22–50 行）—— 本项目最重要的一处"有意偏离"

Python 的 `POST /api/organizations` 会顺手插一行 `scheduling_state`
（[routers/organizations.py](../backend/routers/organizations.py) 第 43 行）：
```python
db.add(SchedulingState(organization_id=organization.id, status="idle", message="尚未开始排课"))
```
**但 `scheduling_state` 是 scheduling 服务的表**（见 [架构说明.md](架构说明.md) §3.2 的铁律 2「禁止跨服务写库」）。
academic 写它会在拆进程后**立刻变成一个隐藏的跨库依赖** —— 而这正是 P1「先同进程验证边界」要提前暴露的问题。

**那 Python 为什么会在那里写？** 因为 Python 的 `GET /api/schedule/status` 要求这行必须存在：
```python
state = db.query(SchedulingState).filter(...).first()
return _state_out(state)          # state 为 None 时 → AttributeError → 500
```
所以在 Python 里「建组织」和「初始化排课状态」被绑在了一起。

**P1 的做法：把初始化搬到属主那边（懒初始化）**

| 服务 | 做什么 |
|---|---|
| **academic** | 只建组织 + 管理员用户，**不碰** `scheduling_state` ✓ |
| **scheduling** | `/api/schedule/status` 查不到记录时，返回默认的 `{status:"idle", message:"尚未开始排课"}`，而不是空指针 500 |

⇒ **对外可见的行为完全一致**（Python 返回那行 idle 状态，Java 返回等价的默认值），
但跨服务写库消失了，且少了一个失败模式（组织建好了、排课状态没建）。

⚠ **过渡期的注意点**（注释里也写了）：如果只启动 Java 版 academic 而 Python 版 scheduling 在处理请求，
**新建的组织在 Python 的 `/status` 上会 500**。P1 是开发期的一次性切换，可以接受；正式迁移时两边一起切。

**`private static String strip(String value)`**（第 159–161 行）：`value == null ? "" : value.strip()`。
⚠ 用 `strip()`（Unicode）而不是 `trim()`（ASCII），与 Python 的 `.strip()` 对齐。

### 5.6 `OrganizationController`（70 行）

文件：[OrganizationController.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/organization/OrganizationController.java)

**两个接口都要求平台管理员**（Python `Depends(require_platform_admin)`）：

| 方法 | 逐行 |
|---|---|
| `list(PageQuery, OrbitUserPrincipal)` | `requirePlatformAdmin(principal);` → `organizationService.list(query)`。⚠ **先校验再干活**（不是"查完再校验"） |
| `create(OrganizationCreateRequest, OrbitUserPrincipal)` | `requirePlatformAdmin(principal);` → `organizationService.create(request)` |

**`private static void requirePlatformAdmin(OrbitUserPrincipal principal)`**（第 62–69 行）

```java
if (principal == null) throw BusinessException.unauthorized("请先登录");
if (!principal.isPlatformAdmin()) throw BusinessException.forbidden(PLATFORM_ADMIN_ONLY);
```
`PLATFORM_ADMIN_ONLY = "仅平台管理员可以管理工作空间"`（第 31 行）。

⚠ **为什么写在代码里而不是用 Spring 的 `hasRole("PLATFORM_ADMIN")`**（注释第 56–60 行，很关键）：
> 用 URL 规则表达时，403 由 Spring 的 `AccessDeniedHandler` 统一生成，**只能给一句通用文案**；
> 而 Python 为每种权限检查准备了**不同的**中文提示（`仅管理员...` / `仅平台管理员...`），**前端会直接显示它**。
> 写成显式检查才能逐字保住这些文案。

⚠ **注意与「管理员」的区别**：`require_admin`（`UserController`）的文案是
`仅管理员可以执行此操作`，**两者不能混用**。

---

## 6. 账号管理（`user` 包）

### 6.1 `User` —— `users` 表实体（116 行）

文件：[User.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/User.java)

```java
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private String username;
    private String displayName;
    private String passwordHash;      // pbkdf2_sha256$310000$盐$摘要
    private String role;              // platform_admin / admin / operator
    private Boolean isActive;
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;
    …
}
```

⚠⚠ **`isActive` / `mustChangePassword` 这两个字段名不能改**（类注释第 15–18 行）：
列名是从**字段名**推导的（`map-underscore-to-camel-case`），
改成 `active` / `changePassword` 就映射不上 `is_active` / `must_change_password`，
**而且 MyBatis-Plus 不会报错，只会查不到值（静默失效）**。
（这就是 `H1`/`H2` 那一类"静默错"的同族问题。）

⚠ **为什么是 `Boolean` 包装类型而不是 `boolean`**：`is_active` 列是 `tinyint(1) NOT NULL`，
但**包装类型允许 null**；用 `boolean` 的话 MyBatis 映射 null 的行为不直观，
而且 `UserUpdateRequest.isActive` 需要表达"没传"（§6.4）。

**`public boolean active()`**（第 113–115 行）
```java
/** is_active 列为 tinyint(1)，可能为 null；null 一律当停用处理。 */
public boolean active() { return Boolean.TRUE.equals(isActive); }
```
⚠ **javadoc 说"可能为 null"，但建表脚本里是 `tinyint(1) NOT NULL`**
（[V1 脚本](../backend-java/orbit-academic/src/main/resources/db/migration/academic/V1__baseline_legacy.sql) 第 54 行）。
两处说法不一致 —— **以代码为准**：这个方法的语义是"**只有明确的 true 才算启用**"，
这是一个**故意保守**的判断（任何异常值都当停用）。用 `Boolean.TRUE.equals(...)` 而不是 `isActive`
就是为了让 null 走"停用"分支（也避免了自动拆箱 NPE）。

⚠ 用的是 `Long id` 而不是 `long`：与"未持久化时 id 为 null"对应。

### 6.2 `UserMapper`（22 行）

文件：[UserMapper.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserMapper.java)

```java
@Mapper
public interface UserMapper extends BaseMapper<User> { }
```
- 只有 `BaseMapper` 的通用方法（`selectById` / `selectOne` / `selectCount` / `insert` / `updateById` / `selectPage`）。
- ⚠⚠ **租户隔离不在这里做**（类注释第 12–15 行，**很重要**）：
  Python 是每个查询**手工**加 `filter(User.organization_id == user.organization_id)`，这里保持一致 ——
  **由 Service 层显式加条件，而不是靠 MyBatis-Plus 的拦截器自动拼**。
  理由：**拦截器一旦被绕过（比如写原生 SQL）就静默失去隔离，而显式条件看得见。**
  ⚠ 反过来说：**每个 `UserMapper` 调用点都要自己确认加了组织条件**（本节里 `UserService` 的每个方法都检查过）。

### 6.3 `UserResponse`（58 行）—— 一个字段名坑

文件：[UserResponse.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserResponse.java)

```java
public record UserResponse(
        Long id, Long organizationId, String organizationName, String username,
        String displayName, String role,
        @JsonProperty("is_active") Boolean isActive,
        Boolean mustChangePassword, LocalDateTime createdAt) {

    public static UserResponse of(User user, String organizationName) { … }
}
```

**实测 Python 的 `GET /api/auth/me` 返回**（类注释里记着原文）：
```json
{"id":1,"organization_id":1,"organization_name":"默认学校","username":"admin",
 "display_name":"系统管理员","role":"platform_admin","is_active":true,
 "must_change_password":false,"created_at":"..."}
```

⚠⚠ **只有 `isActive` 这一个字段显式写了 `@JsonProperty("is_active")`，这不是画蛇添足**（注释第 40–44 行）：
> 全局 `SNAKE_CASE` 策略会把 record 组件 `isActive` 转成 `is_active` 吗？—— **不可靠**。
> Jackson 对 `is` 前缀的布尔属性有特殊处理（**可能变成 `active`**），
> 这与「record 组件」的访问器命名叠加后行为不直观。所以这一个字段显式标注，**不让它依赖推断**。

⚠ **`passwordHash` 绝不出现在这里** —— 这是对外的视图，实体与视图**刻意分开**，
避免以后有人图省事直接把实体返回出去（那会泄露口令哈希）。

⚠ `of(User, String organizationName)` 是**给实体用的**（`UserService` 与 `OrganizationService` 用），
而 `OrbitUserPrincipal.toResponse(String)` 是**给认证主体用的** —— **两个入口，一个形状**。
改字段时**两处都要改**（漏一处会编译不过 ✓，这一点比运行时才发现好）。

### 6.4 两个 DTO

**`UserCreateRequest`（26 行）**
[UserCreateRequest.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserCreateRequest.java)
```java
public record UserCreateRequest(
        @NotNull(message = "Field required") String username,
        @NotNull(message = "Field required") String displayName,
        @NotNull(message = "Field required") String password,
        String role) {}
```
- 对应 Python：`username: str` / `display_name: str` / `password: str` / `role: str = "operator"`。
- 前三个必填（缺 → **422**）；`role` 可省，默认 `operator`（这里**不在 record 上给默认值**，在 Service 里补 —— 见 §6.5 ⚠）。
- ⚠ `username` 是「必填但可为空字符串」→ 空串在 Service 层变成 **400 用户名不能为空**（不是 422），
  所以用 `@NotNull` 而非 `@NotBlank`。

**`UserUpdateRequest`（10 行）**
[UserUpdateRequest.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserUpdateRequest.java)
```java
public record UserUpdateRequest(String displayName, String password, String role, Boolean isActive) {}
```
- 对应 Python `UserUpdate`：四个字段都可选（`Optional[...] = None`）。
- ⚠ **`isActive` 用 `Boolean` 而不是 `boolean`**：要区分「没传」与「传了 `false`」，
  原始类型做不到（这就是 `H1` 那个坑的同源问题：**原始类型装不下"没有值"**）。
  在 `UserService.update` 里，`request.isActive() != null` 才表示"要改停用状态"。

### 6.5 `UserService`（181 行）

文件：[UserService.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserService.java)
Python 对应：[routers/users.py](../backend/routers/users.py)（64 行）

⚠ **注意没有删除接口**（类注释第 21–22 行）：Python 侧也没实现（账号停用走 `is_active=false`）。
**不要"顺手补一个"** —— 那会变成契约之外的新能力。

**`public static final Set<String> VALID_ROLES = Set.of("admin", "operator");`**（第 28 行）
对应 Python 的 `VALID_ROLES = {"admin", "operator"}`。
⚠ **`platform_admin` 不在这个集合里** —— 所以"建一个平台管理员"是做不到的（Python 同样做不到）。

**`PageResponse<UserResponse> list(long organizationId, PageQuery query)`**（第 43–60 行）

| # | 代码 | 说明 |
|---|---|---|
| 1 | `Wrappers.<User>lambdaQuery().eq(User::getOrganizationId, organizationId)` | ⚠ **租户隔离的显式条件**（§6.2 的理由）。`organizationId` 由 Controller 从**认证主体**取，**不从请求参数取**（取参数就能越权） |
| 2 | `if (query.hasKeyword()) wrapper.and(w -> w.like(User::getUsername, query.keyword()).or().like(User::getDisplayName, query.keyword()));` | ⚠ **`.and(...)` 包一层是必须的**：不包的话 `or()` 会把前面的 `eq(organization_id)` 也纳入 or 的另一支，变成「(本组织) OR (显示名匹配)」—— **跨租户泄漏**！<br>生成的是 `(organization_id = ?) AND (username LIKE ? OR display_name LIKE ?)` ✓ |
| 3 | `wrapper.orderBy(true, query.ascending(), User::getId)` | ⚠ 第二参是 `isAsc`。Python 是 `order_by(User.id)`（恒升序）—— 这里允许 `order` 参数改变方向，是**有意的增强**（`PageQuery` 契约）|
| 4 | `userMapper.selectPage(new Page<>(...), wrapper)` | 真分页 |
| 5 | `String organizationName = organizationName(organizationId);` | **列表里所有行共用同一个组织名**（因为它们都是本组织的）→ **只查一次**，不是每行查一次 |
| 6 | `PageResponse.of(records.stream().map(u -> UserResponse.of(u, organizationName)).toList(), page.getTotal())` | 包 `{items,total}` |

⚠ **实测（契约工具）**：`GET /api/users` 在 Java 返回 `{items,total}`，Python 返回**裸数组**。
契约工具用 `list_endpoint=True` **显式承认**这个差异，并检查：`items` 内容逐条等于 Python、
`total` **等于 Python 数组长度**、响应**没有多余字段**
（[compare.py](../tools/contract-diff/compare.py) 第 382–396 行）。**这是有意设计的形状差异，不是 bug。**

**`UserResponse create(long organizationId, UserCreateRequest request)`**（第 75–110 行，`@Transactional`）

| # | 代码 | 说明 / ⚠ |
|---|---|---|
| 1 | `String username = strip(request.username());` | null → ""，再 `strip()` |
| 2 | `if (username.isEmpty()) throw badRequest("用户名不能为空");` | **400**（Python 同一句） |
| 3 | `String role = request.role() == null \|\| request.role().isBlank() ? "operator" : request.role();` | ⚠⚠ **这里是与 Python 唯一一处可察觉的差异，见下方专段** |
| 4 | `if (!VALID_ROLES.contains(role)) throw badRequest("无效的账号角色");` | **400** |
| 5 | `if (userMapper.selectCount(...eq(username)) > 0) throw conflict("用户名已存在");` | **409**。⚠ **不按组织隔离** —— `users` 上是 `UNIQUE KEY ix_users_username (username)`（**全局唯一**），不是 `(organization_id, username)` |
| 6 | `try { passwordHash = passwordEncoder.encode(request.password()); } catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }` | **400 密码至少需要 8 位** |
| 7 | `user.setDisplayName(displayName.isEmpty() ? username : displayName);` | 逐字复刻 Python `display_name=data.display_name.strip() or username` |
| 8 | `setIsActive(true)` / `setMustChangePassword(true)` / `setCreatedAt(UtcTimes.now())` | ⚠ **新账号首次登录必须改密**（Python `must_change_password=True`）。<br>⚠ `created_at` 必须显式写 + **必须 UTC**（同 §5.5 第 6 步） |
| 9 | `userMapper.insert(user);` | 见 §6.7 关于"没有 `db.refresh()`"的说明 |
| 10 | `return UserResponse.of(user, organizationName(organizationId));` | 直接返回刚插的实体 + 组织名 |

**⚠ 差异：`role` 传空串时两边行为不同（静态对照，未实测）**

| 请求体 | Python（`role: str = "operator"`） | Java（第 81 行） |
|---|---|---|
| 不带 `role` 字段 | `data.role` 取默认 `"operator"` → 通过 | `request.role() == null` → `"operator"` → 通过 ✅ 一致 |
| `"role": "superuser"` | `not in VALID_ROLES` → **400 无效的账号角色** | 同 → **400** ✅ 一致 |
| **`"role": ""`** | `"" not in {"admin","operator"}` → **400 无效的账号角色** | `isBlank()` → 视为默认 → **`"operator"` → 200** ❌ **不一致** |
| **`"role": "   "`** | 同上 → **400** | 同上 → **200** ❌ **不一致** |

**根因**：Python 的默认值只在**字段缺失**时生效；`""` 是一个**显式传入的非法值**。
Java 把 `null`（缺失）与 `""`（显式空）**合并**成了同一种"没给"。
契约工具里只有 `role: "superuser"` 这条用例，所以**这个差异测不出来**。
**（要修就是在第 81 行只判 `null`；要确认就加一条 `role: ""` 的用例。）**

**`UserResponse update(long organizationId, long userId, long operatorUserId, UserUpdateRequest request)`**（第 122–163 行，`@Transactional`）

| # | 代码 | 说明 |
|---|---|---|
| 1 | `selectOne(eq(User::getId, userId).eq(User::getOrganizationId, organizationId))` | ⚠ **两个条件一起查**（不是先按 id 查出再判组织）—— 这是**租户隔离的标准写法**：别的组织的 id 会表现成"不存在" |
| 2 | `if (user == null) throw notFound("账号不存在");` | **404**。⚠ 文案是"账号不存在"，不是"无权限"（**不泄漏"这个 id 存在但属于别的学校"**） |
| 3 | `if (request.role() != null) { … }`（第 132–141 行） | 见下 |
| 4 | `if (request.isActive() != null) { … }`（第 142–147 行） | 见下 |
| 5 | `if (request.displayName() != null) { user.setDisplayName(strip(...).isEmpty() ? user.getUsername() : ...); }` | 空白显示名**回退成用户名**（Python `data.display_name.strip() or user.username`） |
| 6 | `if (request.password() != null && !request.password().isEmpty()) { encode…; setMustChangePassword(true); }` | ⚠ **`isEmpty()` 这个条件不能省**：Python 是 `if data.password:`（**真值判断**），传 `""` 时不改密码。<br>⚠ 改密码后**必须把 `mustChangePassword` 置回 true**（Python `user.must_change_password = True`）—— 管理员重置了你的密码，你下次登录就得自己改 |
| 7 | `userMapper.updateById(user);` | ⚠ **这里是全字段更新**（实体上非 null 的字段全写回）。对比 `AuthController.changePassword` 用的是**只带两列的 patch 实体**（§2.5.3 第 6 步）—— **两种写法在同一个模块里并存，要知道区别** |
| 8 | `return UserResponse.of(user, organizationName(organizationId));` | ⚠ **没有重新查库**（见 §6.7） |

**角色分支（第 132–141 行）—— 顺序很重要**
```java
if (request.role() != null) {
    if (!VALID_ROLES.contains(request.role())) throw badRequest("无效的账号角色");
    if (user.getId() == operatorUserId
            && !"admin".equals(request.role()) && !"platform_admin".equals(request.role())) {
        throw badRequest("不能取消自己的管理员权限");
    }
    user.setRole(request.role());
}
```
- ⚠ **先校验合法性、再校验自我保护** —— Python 也是这个顺序。所以
  `PUT /api/users/1 {"role":"superuser"}`（打自己）报的是「无效的账号角色」而不是「不能取消…」。
- ⚠ `user.getId() == operatorUserId`：**一边是 `Long`、一边是 `long`** →
  「`Long` 与基本类型比较」会**自动拆箱**，是数值比较 ✓（**不是**引用比较）。
  ⚠ 但如果 `user.getId()` 是 null 就会 NPE —— 它来自刚查出来的行，不会是 null。
- ⚠ 自我保护的**判定条件**与文案：
  - 目标是自己 **且** 新角色不是 `admin`/`platform_admin` → **400 不能取消自己的管理员权限**
  - ⚠ `VALID_ROLES` 只允许 `admin`/`operator`，而这里又允许 `platform_admin` —— **两处集合不同**，
    这是 Python 的原样（`if user.id == admin.id and data.role not in {"admin", "platform_admin"}`）。

**停用分支（第 142–147 行）**
```java
if (request.isActive() != null) {
    if (user.getId() == operatorUserId && !request.isActive()) throw badRequest("不能停用自己的账号");
    user.setIsActive(request.isActive());
}
```
- **400 不能停用自己的账号**。
- ⚠ `!request.isActive()` 会自动拆箱 —— 这里已经判过 `!= null`，安全。
- **为什么必须有这两条自我保护**（类注释）：没有它们，**一个管理员一次误操作就能把自己锁在系统外面**。
- ⚠ 契约工具里这两条用例**打的就是自己（uid=1 的平台管理员）**，
  而且注释写了恢复方式：`UPDATE users SET role='platform_admin', is_active=1 WHERE id=1;`
  （[compare.py](../tools/contract-diff/compare.py) 第 653–659 行）——
  **它们是"可能把测试环境搞坏"的用例**，改这段代码时先看那两条注释。

**`private String organizationName(long organizationId)`**（第 165–168 行）：查组织名，查不到 → `null`。

**`private static String strip(String value)`**（第 170–172 行）：同 §5.5。

**`public List<User> findByIds(List<Long> ids)`**（第 175–180 行）
```java
if (ids == null || ids.isEmpty()) return List.of();
return userMapper.selectBatchIds(ids);
```
- **给其它领域复用的只读查询**（列表页需要用户显示名时用）。
- ⚠ **早退是为了不发出 `WHERE id IN ()`**（空列表的 SQL 语法在不同库里行为不一，MySQL 直接报错）。
- ⚠ **`selectBatchIds` 不带组织条件** —— 这是有意的（调用方已经知道自己要哪些 id），
  但**新调用方要清楚它绕过了租户过滤**（与 §6.2 那条"显式隔离"的约定并列存在）。

### 6.6 `UserController`（66 行）

文件：[UserController.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/user/UserController.java)

**三个接口都要求管理员**（Python `Depends(require_admin)`）：

| 方法 | 逐行 | 注意 |
|---|---|---|
| `list(PageQuery, principal)` | `userService.list(requireAdmin(principal).organizationId(), query)` | ⚠ **`organizationId` 取自认证主体**，不是请求参数 |
| `create(UserCreateRequest, principal)` | `userService.create(requireAdmin(principal).organizationId(), request)` | 同上 |
| `update(userId, UserUpdateRequest, principal)` | `OrbitUserPrincipal admin = requireAdmin(principal);` → `userService.update(admin.organizationId(), userId, admin.userId(), request)` | ⚠ **`admin.userId()` 作为 `operatorUserId` 传下去**，Service 用它做"不能取消自己/不能停用自己"的判断 —— **这个参数绝不能从请求体里来**（否则自我保护形同虚设） |

**`private static OrbitUserPrincipal requireAdmin(OrbitUserPrincipal principal)`**（第 57–65 行）
```java
if (principal == null) throw BusinessException.unauthorized("请先登录");
if (!principal.isAdmin()) throw BusinessException.forbidden(ADMIN_ONLY);   // 仅管理员可以执行此操作
```
⚠ `isAdmin()` 把 `admin` 与 `platform_admin` 都算管理员（§2.4）。
⚠ **非管理员 → 403，未登录 → 401**：两个分支顺序不能反（先判 null 再判角色）。

⚠ **为什么这两个 Controller 各写一份 `requireXxx` 而不是抽到公共基类**（值得知道，避免"顺手重构"）：
文案不同（`仅管理员…` / `仅平台管理员…`），而**文案是契约**；
抽公共类要么带一个文案参数（可读性变差），要么继承（Spring 的 Controller 继承会带来意外的映射行为）。
**保持两个小方法是当前更安全的选择。**

### 6.7 ⚠ "没有 `db.refresh()`"这件事

Python 的 `create_user` / `update_user` 结尾都是 `db.commit()` + **`db.refresh(user)`**
（[routers/users.py](../backend/routers/users.py) 第 34–36、62–63 行），
`create_organization` 也是 `db.commit()` + `db.refresh(organization)`。

Java 侧**没有这一步**，而是直接返回刚操作过的**内存对象**。差别在哪里：

| 字段 | Java 内存对象 | 从库里读回来 |
|---|---|---|
| `id` | ✅ 已回填（`IdType.AUTO` + `useGeneratedKeys`） | 同 |
| `created_at` | ✅ 我们有 `UtcTimes.now()`（**已截断到秒**） | 同（`datetime` 只存到秒） |
| `is_active` / `must_change_password` | ✅ 显式设过 | 同 |
| 数据库**默认值**列 / **触发器**改过的列 | ❌ 不知道 | ✅ 知道 |

⇒ **只要没有任何列是靠"库里的默认值/触发器"填的，两者等价。**
`users` / `organizations` 两张表**没有默认值**（`V1` 脚本里全是显式列，`NOT NULL` 无 `DEFAULT`），
所以这一步省略是安全的。⚠ **但以后给这两张表加 `DEFAULT` 列或触发器时，这里就会变成差异** ——
这正是[第 1 篇](代码导读.md) §1.1 里 `UtcTimes` 那个坑的同一个主题
（"POST 从内存返回、GET 从库里读回，格式不一致"）。

---

## 7. 运维接口 —— `HealthController`（139 行）

文件：[HealthController.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/health/HealthController.java)

（⚠ 回顾 §0.3：`/api/system/*` 三个运维接口**在 scheduling 模块**，不在本篇范围。
academic 的运维面就是这一个接口。）

**它是 P0 骨架的验证点**，也是「一条命令能不能跑通」的证据。

### 7.1 契约：逐字保留 + 安全追加

Python 原版返回：
```json
{"ok": true, "redis": {"available": true, "used_memory": 1048576, "used_memory_human": "1.00M"}}
```

Java 返回：
```json
{"ok": true, "app": "orbit-academic", "version": "0.1.0-SNAPSHOT",
 "redis": {...}, "db": {...}, "flyway": {...}}
```

- ⚠ **`ok` 与 `redis`（含其子字段结构）逐字保留**；另外**追加** `app` / `version` / `db` / `flyway`。
- **追加是安全的**：前端只读它认识的键，不会因为多出字段而失败。
  （⚠ 注意这与列表接口不一样 —— 列表接口多出字段会被契约工具抓
  `Java 响应多出字段`；health 这种"探针接口"两边本来就允许各自扩展。）
- ⚠ **`ok` 的语义与 Python 版一致 —— 「进程活着」就是 `true`**。
  依赖项的可用性放在各自的子对象里报告，**不把 `ok` 变成整个依赖链的与运算**。
  这样 health 才始终可用，**不会因为 Redis 挂了就返回 500**（那会同时打掉监控和排障）。

### 7.2 构造器与依赖（第 48–58 行）

```java
public HealthController(DataSource dataSource,
                        ObjectProvider<StringRedisTemplate> redisTemplate,
                        ObjectProvider<Flyway> flyway,
                        @Value("${spring.application.name:orbit}") String appName,
                        @Value("${orbit.version:0.1.0-SNAPSHOT}") String appVersion)
```

| 依赖 | 为什么用 `ObjectProvider` | 对比 |
|---|---|---|
| `DataSource` | 直接用（数据源不可能没有，没有就启动失败） | |
| `StringRedisTemplate` | ⚠ **`ObjectProvider`**：Redis 客户端**可能不存在**（比如没引 starter 的裁剪部署）→ `getIfAvailable()` 返回 null 时报告 `available:false`，**而不是让整个应用启动失败** | `LoginAttemptGuard` 直接注入（因为它需要"Redis 挂了也能跑"的运行时降级，见 §2.2） |
| `Flyway` | 同上 | |
| `appName` / `appVersion` | `@Value` 带**默认值**（`spring.application.name:orbit` / `orbit.version:0.1.0-SNAPSHOT`） | ⚠ `orbit.version` 在 `application.yml` 里**没有配**，所以走默认值。想让它显示真实版本，得在 yml 或构建时注入 |

### 7.3 逐方法

**`@GetMapping("/api/health") public Map<String, Object> health()`**（第 60–70 行）

```java
Map<String, Object> body = new LinkedHashMap<>();
body.put("ok", true);
body.put("app", appName);
body.put("version", appVersion);
body.put("redis", redisStatus());
body.put("db", dbStatus());
body.put("flyway", flywayStatus());
return body;
```
- ⚠ **`LinkedHashMap` 不是随便选的**：普通 `HashMap` 的迭代顺序不确定，
  响应里字段顺序会**每次都不一样**（对契约比对和肉眼 diff 都是噪音）。用 `LinkedHashMap` 固定成插入顺序。
- ⚠ **直接返回 `Map`，没有 DTO**：这个接口的形状是"允许追加"的，用一个 record 反而会在加字段时处处要改。
- ⚠ **三个私有方法是"每次调用都真的去探一次"**（不是缓存值）—— 所以 health 是**有副作用的探测**，
  不要把它放到高频路径上。

**`private Map<String, Object> redisStatus()`**（第 73–95 行）

| # | 代码 | 说明 |
|---|---|---|
| 1 | `StringRedisTemplate template = redisTemplate.getIfAvailable();` | 没有 bean → null |
| 2 | `if (template == null) { out.put("available", false); return out; }` | ⚠ **只输出 `available`**（与 Python `{"available": False}` 同形状）—— 不补 `used_memory: 0`，因为 Python 也没补 |
| 3 | `Properties info = template.execute((RedisConnection c) -> c.serverCommands().info("memory"));` | ⚠ **用 `execute` 拿到 `RedisConnection` 是因为 `StringRedisTemplate.opsForValue()` 给不出 `INFO` 命令**（那是连接级命令）。对应 Python 的 `client.info("memory")` |
| 4 | `if (info == null) { available=false; return }` | 防御 |
| 5 | `out.put("available", true)` | |
| 6 | `out.put("used_memory", parseLong(info.getProperty("used_memory")))` | ⚠ **`parseLong` 是自定义的容错解析**（第 132–138 行）：null → `0L`、非数字 → `0L`。对应 Python `int(info.get("used_memory", 0))` 的意图（不过 Python 那句在值是脏字符串时会抛异常 → 被外层 `except` 抓掉变成 `available:false`；**Java 这里会返回 `available:true` + `used_memory:0`**。⚠ **这是一处细微差异，静态对照，未实测**） |
| 7 | `out.put("used_memory_human", info.getProperty("used_memory_human", "0B"))` | **默认值 `"0B"` 与 Python 一致** |
| 8 | `catch (Exception e) { log.warn("Redis 健康检查失败: {}", e.getMessage()); out.put("available", false); }` | ⚠ **健康检查自己抛异常就白做了**：Redis 挂了必须仍然返回 200 + `available:false` |

**`private Map<String, Object> dbStatus()`**（第 97–109 行，**Java 特有，Python 没有**）

```java
try (Connection connection = dataSource.getConnection()) {          // ⚠ try-with-resources：连接必须还回池
    DatabaseMetaData meta = connection.getMetaData();
    out.put("available", true);
    out.put("product", meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
    out.put("catalog", connection.getCatalog());
} catch (Exception e) { log.warn(...); out.put("available", false); }
```
- ⚠ **`try (Connection …)` 不能写成裸 `getConnection()`** —— 漏了关闭会**把 Hikari 连接池耗尽**
  （`maximum-pool-size: 10`），而 health 会被探针**周期性调用**，几十秒就把池占满。
  这是"健康检查把自己搞挂"的经典写法，**这里是对的**。
- ⚠ `available:true` 只在拿到连接之后才写 → 连不上时输出的是**只有 `available:false`**（不补 product/catalog），
  与 `redisStatus` 的形状规则一致。

**`private Map<String, Object> flywayStatus()`**（第 111–130 行，**Java 特有**）

```java
Flyway f = flyway.getIfAvailable();
if (f == null) { out.put("available", false); return out; }
try {
    MigrationInfo current = f.info().current();
    out.put("available", true);
    out.put("version", current == null ? null : String.valueOf(current.getVersion()));
    out.put("description", current == null ? null : current.getDescription());
    out.put("pending", f.info().pending().length);
} catch (Exception e) { log.warn("Flyway 状态检查失败: {}", e.getMessage()); out.put("available", false); }
```
- **报告当前基线/迁移版本 —— P0 阶段用它证明 Flyway 真的接上了**
  （⚠ 因为 Flyway 会**静默失效**：只声明 `flyway-core` 不引 starter 时"不报错、不建表"，
  见 [踩坑总结](踩坑总结.md) B2/D1 与 §8.5）。
- ⚠ **`f.info()` 被调用了两次**（`current()` 一次、`pending()` 一次）—— 每次都读 `flyway_schema_history` 表。
  只读、可接受；但要知道 health 一次调用 = **数据库 2 次 + Redis 1 次 + 连接 1 次**。
- ⚠ `current == null` 时把 `version` / `description` 显式放成 `null`，
  而 `json.default-property-inclusion: always` 会**输出 `null`**（不省略）—— 保持字段形状稳定。

**`private static long parseLong(String value)`**（第 132–138 行）
```java
try { return value == null ? 0L : Long.parseLong(value.trim()); }
catch (NumberFormatException e) { return 0L; }
```
- ⚠ **一个空 catch 到 0 的解析**：故意不抛（health 不能因为一个脏值就 500）。
  对比 §7.3 第 6 步提到的那处差异。

---

## 8. `config` 与基础设施

### 8.1 `JwtConfig`（29 行）—— 为什么装配在服务侧

文件：[JwtConfig.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/config/JwtConfig.java)

```java
@Configuration
public class JwtConfig {
    @Bean
    public JwtService jwtService(@Value("${orbit.jwt.secret}") String secret,
                                 @Value("${orbit.jwt.ttl-seconds:28800}") long ttlSeconds) {
        return new JwtService(secret, ttlSeconds);
    }
}
```

| 细节 | 为什么 |
|---|---|
| ⚠ **`JwtService` 是 `orbit-common` 的类，但 Bean 定义在服务侧** | `orbit-common` 刻意保持**薄共享模块**（只引 spring-web，不给 WebFlux 网关带进 Servlet 容器）。让它带 `@Component`/`@Configuration` 会把「共享逻辑」和「容器装配」混在一起；而且各服务的策略需求可能不同（**将来 scheduling 只校验不签发**）。<br>同样的做法在 solver-core 里也用过：`RuleRegistry` 是纯数据结构，"有哪些内置规则"的装配交给 `compile.BuiltinRules` |
| ⚠ **`${orbit.jwt.secret}` 没有默认值**（没有 `:` 冒号默认） | 没配就**启动失败**（占位符解析不了）。这是**有意的**：弱密钥/没密钥是安全问题，**不能静默用一个内置默认值**（对比：Python 的 `APP_SECRET_KEY` 有默认值 `"orbit-development-secret-change-me"`） |
| ⚠ **`ttl-seconds:28800` 的默认值 = 8 小时**，与 Python `TOKEN_TTL_SECONDS` 默认值一致 | `application.yml` 的注释记着一次真实的修正：原来写 43200（12h），**同一份配置、同样登录一次，Java 侧有效期比 Python 长 4 小时** —— "没必要的**行为差异**" |
| `JwtService(secret, ttl)` 的两道校验 | ① `secret` 为 null/blank → `IllegalStateException("orbit.jwt.secret 未配置（应来自 APP_SECRET_KEY）")`；② 密钥 **< 32 字节 → 启动失败**（HS256 要求 ≥256 bit）。<br>⚠ **短密钥"偷偷派生"比失败更糟**：会让人以为配置没问题（[第 1 篇](代码导读.md) §1.9） |

⚠ **一个值得注意的默认值不一致（静态对照）**：Java 的 yml 里 `secret: ${APP_SECRET_KEY:change_me}`
—— `"change_me"` 只有 **9 字节** < 32，**没设环境变量时应用会启动失败**。
而 Python 的默认值是 `"orbit-development-secret-change-me"`（33+ 字节），**能直接跑起来**。
⇒ 「开箱即用」这件事上两边不同：**Python 不配也能起，Java 必须先设 `APP_SECRET_KEY`**。
（这是从两处代码读出来的，**没有实测**；如果 P1 的启动脚本里已经导出这个变量，就不会遇到。）

### 8.2 `MybatisPlusConfig`（42 行）—— 一个"静默失效"的典型

文件：[MybatisPlusConfig.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/config/MybatisPlusConfig.java)

```java
@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

⚠⚠ **为什么必须有这个分页拦截器**（类注释，与[踩坑总结](踩坑总结.md) H6 是同一件事）：
MyBatis-Plus 的 `selectPage(Page, Wrapper)` **不会自己分页** ——
它把分页交给 `PaginationInnerInterceptor` 去**改写 SQL**（加 `LIMIT`）。不注册这个拦截器时：

- **不报错**，构建通过、启动成功；
- `selectPage` 返回**全部**记录（SQL 里真没有 `LIMIT`），`page.getTotal()` 也拿不到正确的总数；
- 表现是「分页参数好像没生效」——**而接口看起来是好的**。

⚠ **所以 P1 的验证里有一条是实测** `?size=1` 只返回 1 条、而 `total` 仍然是总数 ——
**光看代码看不出这个拦截器有没有生效。**
（契约工具里还有一条专门的用例：`assignments 列表默认页（应为 20 条 + total 全量）`，
以及"列表接口默认补 `size=200` 做全量比对"的做法，见 [compare.py](../tools/contract-diff/compare.py) 第 870–873 行。）

| 细节 | 为什么 |
|---|---|
| `new PaginationInnerInterceptor(DbType.MYSQL)` | ⚠ **明确指定 MySQL**：`DbType` 也用来做**分页方言与 count 语句生成**。不指定时它会去猜（靠 JDBC 元数据），猜错就是 SQL 语法错 |
| ⚠ 3.5.9+ 需要额外依赖 `mybatis-plus-jsqlparser` | `PaginationInnerInterceptor` 依赖 jsqlparser 改写 SQL，官方**不再随 starter 一起发**。缺了它是**编译不过**（运气好）或者运行时 `NoClassDefFoundError`。`pom.xml` 里已经显式引了（见 §8.5） |
| 这个 Bean 对**全模块**生效 | 所以 3a 的所有列表接口也靠它。**删掉它 = 全模块分页静默失效** |

### 8.3 `AcademicApplication`（50 行）—— 两个扫描配置

文件：[AcademicApplication.java](../backend-java/orbit-academic/src/main/java/com/orbit/academic/AcademicApplication.java)

```java
@SpringBootApplication(scanBasePackages = {"com.orbit.academic", "com.orbit.common"})
@MapperScan(basePackages = "com.orbit.academic", annotationClass = Mapper.class)
public class AcademicApplication {
    public static void main(String[] args) { SpringApplication.run(AcademicApplication.class, args); }
}
```

**① 为什么 `scanBasePackages` 要显式写 `com.orbit.common`**

`@SpringBootApplication` 默认**只扫描自己所在包及其子包**（`com.orbit.academic`）。
而 `orbit-common` 里的 `ApiExceptionHandler`（**全局错误体**）在 `com.orbit.common` 下 ——
不显式加进来，它**不会被注册**，于是：
- 错误响应会退回 Spring 默认的 `ProblemDetail`，**契约对不上**；
- 而且**不报错**：构建通过、启动成功，**只有契约测试会红** —— 典型的静默失效。

**② Mapper 扫描：`@MapperScan` + `annotationClass` 过滤**

本项目的约定是「**实体 / Mapper / Controller / Service 同放在一个领域包**里」
（所以 Mapper 在 `com.orbit.academic.user` 而**不是** `...user.mapper`）。踩过两次（类注释里记着）：

| 试过的写法 | 结果 |
|---|---|
| `@MapperScan("com.orbit.academic.**.mapper")` | 期望 `mapper` 子包，而 Mapper 在领域包根下 → **一个都没注册**，启动失败（`No qualifying bean of type 'UserMapper'`） |
| 去掉 `@MapperScan`、只留接口上的 `@Mapper` | **也不行**：MyBatis-Plus 3.5.9 的自动扫描没把 `com.orbit.academic.user` 下的接口捞起来，同样启动失败 |
| ✅ `@MapperScan(basePackages = "com.orbit.academic", annotationClass = Mapper.class)` | 扫整个包 → 领域包下的 Mapper 都能找到；只注册带 `@Mapper` 的接口 → 将来有人在领域包里加一个**服务接口**（比如 `SnapshotProvider`）**不会被误注册成 Mapper** |

⇒ **加新 Mapper 时只要记得标 `@Mapper`**，不用回来改这个启动类。
（这也解释了为什么每个 Mapper 上都留着 `@Mapper` —— 它**不是冗余**，是 `annotationClass` 过滤的依据。）

### 8.4 [`application.yml`](../backend-java/orbit-academic/src/main/resources/application.yml) 逐段

| 段落 | 关键内容 | ⚠ 要点 |
|---|---|---|
| `server.port` | `8081` | 与 scheduling `:8083` 区分；网关按路径分流 |
| `spring.datasource.url` | `characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci&serverTimezone=UTC` | ⚠⚠ **`characterEncoding` 必须是 Java 字符集名（`UTF-8`），不是 MySQL 的名字（`utf8mb4`）** —— 写成 `utf8mb4` **直接连不上**：`Unsupported character encoding 'utf8mb4'`。utf8mb4 靠 `connectionCollation` 指定（[踩坑总结](踩坑总结.md) C1） |
| `hikari` | `pool-name: academic-pool`，`maximum-pool-size: 10`，`minimum-idle: 2` | ⚠ 池名带服务前缀，**两个服务各自一个池**，日志里能区分。⚠ 这也解释了 §7.3 `dbStatus()` 为什么必须 `try-with-resources`（池只有 10 个连接） |
| `spring.data.redis.url` | `redis://127.0.0.1:6379/0` | 与 Python 共用（**登录失败计数的 key 也共用**，§2.2） |
| `spring.flyway.table` | `flyway_schema_history_academic` | ⚠ **微服务共享一个库时，每个服务用独立 history 表**，各自管自己的表 |
| `spring.flyway.locations` | `classpath:db/migration/academic` | 只跑本服务的迁移脚本 |
| `spring.flyway.baseline-on-migrate` / `baseline-version` | `true` / **`0`** | ⚠⚠ **这两个值不能随便改**（yml 里写了完整推演）：<br>· 关掉 `baseline-on-migrate` → Flyway 安全检查拒绝启动：`Found non-empty schema(s) but no schema history table.`（共享库 schema 非空、本服务还没 history 表）<br>· `baseline-on-migrate: true` + `baseline-version: 1`（**Flyway 默认值**）→ 基线把「版本 ≤ 1」的迁移全部视为已应用 → **V1 被静默跳过**：表根本没建、却不报错<br>· ✅ `true` + `0`：基线只抑制版本 ≤ 0 的迁移，V1 照常执行；而 V1 写成**幂等脚本**（`CREATE TABLE IF NOT EXISTS`），在已有表上就是 no-op。四种情况都对（现有库 / 全新空库 academic 先起 / 全新空库 scheduling 后起 / 顺序颠倒时**响亮失败**） |
| `spring.jackson.default-property-inclusion` | `always` | ⚠ **必须是 `always`（默认），不能是 `non_null`**。实测 Python 侧**包含 `null` 字段**（如 `{"created_by":1,"published_by":null}`）；写成 `non_null` 会**悄悄删掉**它们 → 契约对不上，而且前端读 `undefined` 与 `null` 行为不同 |
| `spring.jackson.property-naming-strategy` | `SNAKE_CASE` | ⚠ **一处配置解决全部字段命名**：Java 写 `organizationId`，JSON 自动 `organization_id`，不用每个 DTO 贴 `@JsonProperty`。<br>⚠ **例外只有 `UserResponse.isActive`**（§6.3）；错误体里的字段名也复用它（`ApiExceptionHandler.jsonName` 刻意复用 Jackson 的命名策略，**两者不可能漂移**） |
| `orbit.jwt.secret` | `${APP_SECRET_KEY:change_me}` | ⚠ 与 Python 同源（`.env` 的 `APP_SECRET_KEY`）；实测 49 字节 ✓ 满足 HS256。⚠ **默认值 `change_me` 只有 9 字节 → 启动失败**（见 §8.1） |
| `orbit.jwt.ttl-seconds` | `${TOKEN_TTL_SECONDS:28800}` | ⚠ 对齐 Python 的 8 小时（见 §8.1） |
| `orbit.tenant.default-organization-id` | `1` | ⚠ **全 `backend-java` 只有这一处出现，没有任何读取点**（`grep defaultOrganizationId` 无结果）→ **当前是死配置**。不要以为它影响了租户解析：本模块的租户 id **一律来自认证主体**（`principal.organizationId()`），没有"默认组织"的回退逻辑 |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus,threaddump,heapdump` | ⚠ 暴露了 `heapdump` / `threaddump` —— **它们能读到内存/线程栈**，`SecurityConfig` 只放行了 `/actuator/health*` 与 `/actuator/info`，所以 `metrics`/`heapdump` 等**仍要求认证** ✓（这条放行清单与这里是一对，改动时要一起想） |

⚠ **这份 yml 里没有 `orbit.login.max-failures` / `orbit.login.window-seconds`** —— 限流阈值走注解默认值 5 / 300（§2.2）。

### 8.5 [`pom.xml`](../backend-java/orbit-academic/pom.xml) 里三个"静默失效"相关的依赖

| 依赖 | 为什么单独提它 |
|---|---|
| `mybatis-plus-spring-boot4-starter` | 数据访问（**不用 JPA**：条件 UPDATE 需要拿受影响行数） |
| `mybatis-plus-jsqlparser` | ⚠ 分页拦截器依赖 jsqlparser **被拆到独立构件**；不引的后果是**静默的**（`selectPage` 不报错、只是不分页）。pom 里的注释与 §8.2 是同一件事 |
| `spring-boot-starter-flyway`（**不是** `flyway-core`） | ⚠⚠ Spring Boot 4 起自动配置被拆成独立模块，**flyway 的自动配置不在 `spring-boot-autoconfigure` 里**。只声明 `flyway-core` 会「**静默失效**」：不报错、不建表、也不建 history 表 —— 启动一切正常，**只是迁移从来没跑过**。必须走 starter。pom 注释原话：**「这个坑只有真的连库跑起来才会发现」** |
| `flyway-mysql` | ⚠ Flyway 10+ 起数据库方言拆成独立模块，MySQL 要单独引 |
| `spring-boot-starter-security` | ⚠ 一上 classpath，**所有请求默认都要认证** —— 所以 `SecurityConfig` 里的放行清单不是可选项（[踩坑总结](踩坑总结.md) C3） |
| `spring-boot-starter-data-redis` / `starter-actuator` | 限流 / health |
| `orbit-common` | 契约层（**JWT 依赖由它提供**：`JwtService` 在里面，两个服务都要校验 token） |

---

## 9. 已知差异 / 待验证清单（改代码前先扫这张表）

**A. 有意为之、不要"修好"**

| # | 事 | 在哪 |
|---|---|---|
| A1 | Java 的 token 是**标准 JWT**，与 Python 不通用（灰度切换时老 token 失效、要重新登录一次） | `JwtService`（第 1 篇 §1.9） |
| A2 | 建组织时**不写** `scheduling_state`，改由 scheduling 懒初始化 | §5.5 |
| A3 | 列表接口在 Java 返回 `{items,total}`、Python 返回**裸数组** | §6.5 / [第 1 篇](代码导读.md) §1.3 |
| A4 | 「当前密码不正确」是 **400** 而不是 401 | §2.5.3 |
| A5 | 「新密码至少 8 位」是 **400**（编码器抛异常转换），不是 422 | §2.5.3 / §2.1 |
| A6 | 401 文案**分三种**；403 的"管理员/平台管理员"**也是两种不同文案** | §3.2 / §5.6 / §6.6 |
| A7 | 登录失败**滑动窗口**（每次失败都重置 TTL），不是固定窗口 | §2.2 |
| A8 | Redis 不可用时**降级放行**（可用性优先） | §2.2 |
| A9 | 过滤器**不写响应**；未认证由 `AuthenticationEntryPoint` 统一写 | §3.1 |
| A10 | 权限检查**写在代码里**（`isAdmin()` / `isPlatformAdmin()`），不用 `hasRole` | §4.5 / §5.6 |

**B. 从代码读出来的差异 / 风险（**未做运行时验证**，改了要补测试）**

| # | 事 | 影响 | 建议的最小验证 |
|---|---|---|---|
| B1 | `UserService.create` 把 `role: ""` 当成默认值 `operator`，Python 会报 **400 无效的账号角色** | 行为不等价 | 加一条 `role: ""` 的契约用例 |
| B2 | 改 `ITERATIONS` 常量**不会**让旧哈希校验失败（注释说会） | 注释误导；真正的风险是"悄悄降低强度" | 用 Python 生成一条固定向量 + 一条"轮数不同"的哈希做断言 |
| B3 | hex 大小写：Java 的 `parseHex` 收大写、Python 的字符串比较不收 | 手工改过的哈希行行为不同 | 加一条大写 hex 的断言，看希望哪种行为 |
| B4 | `LoginAttemptGuard.recordFailure` 的 `INCR` 与 `EXPIRE` **不是原子的**（Python 用 pipeline） | `EXPIRE` 失败会留下**永不过期的计数** → 永久限流 | 用 Lua 脚本或 pipeline 收口 |
| B5 | Java **没有** `REDIS_REQUIRED=true` 的启动校验 | Redis 挂了限流**静默失效**（Python 会启动失败） | 一个 `ApplicationRunner` 里 ping 一次 |
| B6 | `SecurityConfig` 只 `permitAll` 了 `POST /api/auth/login`，**`GET /api/auth/login` 未带 token → 401**，Python 给 405 | 错误方法的状态码不同 | 加一条 `GET /api/auth/login` 的用例 |
| B7 | `OrbitUserPrincipal.getAuthorities()` 的 `toUpperCase()` **没有 Locale**（`LoginAttemptGuard` 用了 `Locale.ROOT`） | 土耳其语 locale 下不一致；当前无实际影响（不用 `hasRole`） | 统一成 `toUpperCase(Locale.ROOT)` |
| B8 | `OrbitUserDetailsService.loadUserByUsername` 用 `trim()`（ASCII），Python 用 `.strip()`（Unicode） | 换调用点可能漂移 | 统一成 `strip()` |
| B9 | `OrbitUserPrincipal` 的 emoji 口令路径（代理对 → UTF-8）**没有被测试覆盖**（`@DisplayName` 提到了，方法体里没有） | 含 emoji 的口令可能校验不上 | 加一条 Python 生成的 emoji 向量 |
| B10 | `HealthController.redisStatus`：`used_memory` 是脏值时 Java 给 `available:true, used_memory:0`，Python 会变成 `available:false` | 极端输入下响应不同 | 加一条断言（或接受为"Java 更宽容"） |
| B11 | `User.active()` 的 javadoc 说 `is_active` "可能为 null"，但建表脚本是 `NOT NULL` | 注释与 schema 不一致 | 改注释（或加 `DEFAULT`） |
| B12 | `JwtConfig` 的默认密钥 `change_me`（9 字节）会让**未设 `APP_SECRET_KEY` 时启动失败**，而 Python 有可用的开发默认值 | "开箱能跑"这件事两边不同 | 确认启动脚本导出了 `APP_SECRET_KEY` |
| B13 | `orbit.tenant.default-organization-id: 1` **没有任何读取点** | 死配置，容易误以为它在生效 | 删掉，或在注释里标明"预留" |
| B14 | `PythonPbkdf2PasswordEncoder.utf8(String)` **没有调用点** | 死代码，容易被误读为"这里显式用了 UTF-8" | 删掉 |
| B15 | `UserService.update` 用**全字段更新**（`updateById(user)`），而 `AuthController.changePassword` 用**两列 patch** | 同一个模块两种写法；前者在并发下覆盖面更大 | 至少在注释里点明两种写法的取舍 |

**C. 实测过的事实（可以直接引用）**

| # | 事实 | 来源 |
|---|---|---|
| C1 | 连续失败 5 次 → 401；**第 6 次 → 429 + `Retry-After`**（两边都验过） | [compare.py](../tools/contract-diff/compare.py) `check_login_rate_limit` |
| C2 | `auth/login` 缺字段 → 422；错误口令 → 401；**非法 JSON → 422** | 同上，用例集 |
| C3 | `auth/me` 未认证 → 401 | 同上 |
| C4 | `organizations` 新建空名称 → 400；缺字段 → 422；口令太短 → 400 | 同上 |
| C5 | `users` 新建空用户名 → 400、非法角色 → 400、口令太短 → 400、缺字段 → 422；修改不存在 → 404；自降权 / 自停用 → 400 | 同上 |
| C6 | Python 的 `/api/auth/me` 响应形状（含 `organization_name`、`is_active`、`must_change_password`） | `UserResponse` 类注释里记的原文 |
| C7 | `?size=1` 只返回 1 条、`total` 仍是总数（证明分页拦截器生效） | [踩坑总结](踩坑总结.md) H6 / `MybatisPlusConfig` 注释 |
| C8 | Python 的 `GET /api/grades?page=1&size=1` 仍返回全部（**忽略分页参数**） | [第 1 篇](代码导读.md) §1.3 |
| C9 | **`orbit:login_failures:<sha256(user\|ip)>` 这个 key 在两边是故意一致的** —— 所以测限流必须给两边用不同的用户名 | [compare.py](../tools/contract-diff/compare.py) 第 462–465 行 |
| C10 | `APP_SECRET_KEY` 实测 49 字节，满足 HS256 的 ≥32 字节要求 | `application.yml` 注释 / `JwtService` 注释 |

---

## 本篇小结

本篇这 27 个类，回答的是**两个问题**：

**① 「谁可以进来？」**

| 类 | 它守的一致性 |
|---|---|
| `PythonPbkdf2PasswordEncoder` | **能读懂 Python 写的密码哈希**（迁移后老用户能登录） |
| `LoginAttemptGuard` | 限流的 key / 阈值 / `Retry-After` 语义与 Python 一致（**双跑时计数还共享**） |
| `OrbitUserDetailsService` + `OrbitUserPrincipal` | 「每请求查库」「停用即失效」「登录失败不区分原因」这三条行为 |
| `AuthController` | 状态码 + **逐字文案** + `Retry-After` 的完整链路 |
| `JwtAuthenticationFilter` + `AuthFailure` + `SecurityConfig` | **三种 401 文案**与「未认证是 401 不是 403」 |
| `UserController` / `OrganizationController` | 两种 403 文案（管理员 / 平台管理员） |

**② 「这所学校长什么样？」**

`Organization` / `OrganizationMapper` / `OrganizationService` / `OrganizationController`（§5）
与 `User` / `UserResponse` / `UserService` / `UserController`（§6）—— 套路与 3a 的主数据 CRUD 相同：
**Controller 只做三件事、Service 里显式加租户条件、错误文案逐字来自 Python**。

**最后记住三件事（本篇最容易踩的）**：

1. **登录失败的计数 key 与 Python 共用** —— key 一个字都不能改，否则灰度期限流会被绕过（§2.2）。
2. **过滤器的职责边界**：只把 token 变成上下文 + 写下失败原因，**从不自己写响应**（§3.1）。
3. **权限不在 `SecurityConfig` 里** —— 它在 Controller 的显式检查里，因为**文案是契约**（§4.5、§5.6、§6.6）。
