@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo [Orbit] 正在启动 Java 微服务开发环境...
call backend-java\start.cmd
if errorlevel 1 (
  echo [失败] Java 服务启动失败。
  pause
  exit /b 1
)

if not exist "frontend\node_modules" (
  echo [前端] 首次安装依赖...
  pushd frontend
  call npm.cmd install
  if errorlevel 1 (
    popd
    echo [失败] 前端依赖安装失败。
    pause
    exit /b 1
  )
  popd
)

start "Orbit Frontend" /D "%~dp0frontend" cmd /k "npm.cmd run dev"

echo.
echo [完成] Orbit 正在运行：
echo   Web        http://localhost:5173
echo   Gateway    http://127.0.0.1:8080
echo   Academic   http://127.0.0.1:8081
echo   Scheduling http://127.0.0.1:8083
echo   Worker     http://127.0.0.1:8084/actuator/health
echo   Assistant  http://127.0.0.1:8085/actuator/health
echo.
echo Python 旧版对照请运行 start-dev-python.cmd
timeout /t 2 /nobreak >nul
start "" "http://localhost:5173"
endlocal
