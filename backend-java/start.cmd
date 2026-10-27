@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

if not exist "..\.env" (
  echo [失败] 找不到项目根目录 .env
  exit /b 1
)
for /f "usebackq tokens=1,* delims==" %%A in ("..\.env") do set "%%A=%%B"

if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>&1
if errorlevel 1 (
  echo [失败] 找不到 Java。请安装 JDK 21 并配置 JAVA_HOME。
  exit /b 1
)
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%~V"
echo %JAVA_VERSION% | findstr /b "21." >nul
if errorlevel 1 (
  echo [失败] 本项目必须使用 JDK 21，当前版本为 %JAVA_VERSION%。
  exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
  echo [基础设施] 正在启动 Docker Desktop...
  if exist "C:\Program Files\Docker\Docker\Docker Desktop.exe" (
    start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
  ) else (
    echo [失败] 未找到 Docker Desktop，请先启动 Docker。
    exit /b 1
  )
  for /l %%I in (1,1,90) do (
    docker info >nul 2>&1 && goto docker_ready
    timeout /t 2 /nobreak >nul
  )
  echo [失败] Docker 在三分钟内没有就绪。
  exit /b 1
)

:docker_ready
echo [基础设施] 启动 MySQL 与 Redis...
docker compose up -d
if errorlevel 1 exit /b 1
for /l %%I in (1,1,60) do (
  docker inspect --format "{{.State.Health.Status}}" orbit-mysql 2>nul | findstr /c:"healthy" >nul && goto mysql_ready
  timeout /t 2 /nobreak >nul
)
echo [失败] MySQL 在两分钟内没有就绪。
exit /b 1

:mysql_ready
echo [构建] 编译全部 Java 服务...
call mvn.cmd -B package -DskipTests
if errorlevel 1 (
  echo [失败] Maven 构建失败。
  exit /b 1
)

echo [服务] 启动 academic...
start "orbit-academic" /D "%~dp0" cmd /k "java -jar orbit-academic\target\orbit-academic-0.1.0-SNAPSHOT.jar"
call :wait_url "http://127.0.0.1:8081/api/health" "academic"
if errorlevel 1 exit /b 1

echo [服务] 启动 scheduling...
start "orbit-scheduling" /D "%~dp0" cmd /k "java -jar orbit-scheduling\target\orbit-scheduling-0.1.0-SNAPSHOT.jar"
call :wait_url "http://127.0.0.1:8083/actuator/health" "scheduling"
if errorlevel 1 exit /b 1

echo [服务] 启动 solver-worker...
start "orbit-solver-worker" /D "%~dp0" cmd /k "java -jar orbit-solver-worker\target\orbit-solver-worker-0.1.0-SNAPSHOT.jar"
call :wait_url "http://127.0.0.1:8084/actuator/health" "solver-worker"
if errorlevel 1 exit /b 1

echo [服务] 启动 gateway...
start "orbit-gateway" /D "%~dp0" cmd /k "java -jar orbit-gateway\target\orbit-gateway-0.1.0-SNAPSHOT.jar"
call :wait_url "http://127.0.0.1:8080/actuator/health" "gateway"
if errorlevel 1 exit /b 1

echo [服务] 启动 assistant...
start "orbit-assistant" /D "%~dp0" cmd /k "java -jar orbit-assistant\target\orbit-assistant-0.1.0-SNAPSHOT.jar"
call :wait_url "http://127.0.0.1:8085/actuator/health" "assistant"
if errorlevel 1 exit /b 1

echo [完成] Java 核心服务已全部就绪。
exit /b 0

:wait_url
for /l %%I in (1,1,60) do (
  curl.exe -fsS %~1 >nul 2>&1 && exit /b 0
  timeout /t 2 /nobreak >nul
)
echo [失败] %~2 在两分钟内没有就绪：%~1
exit /b 1
