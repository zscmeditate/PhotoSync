# PhotoSync 后端一键启动脚本
# 用法：
#   .\start-backend.ps1         启动 PostgreSQL + MinIO + 后端服务
#   .\start-backend.ps1 -Stop   停止后端服务与本地依赖
param(
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Root       = $PSScriptRoot
$Local      = Join-Path $Root '.local'
$Pgsql      = Join-Path $Local 'pgsql'
$PgBin      = Join-Path $Pgsql 'bin'
$PgData     = Join-Path $Local 'pgdata'
$MinioExe   = Join-Path $Local 'minio\minio.exe'
$MinioData  = Join-Path $Local 'minio-data'
$Logs       = Join-Path $Local 'logs'
$ServerDir  = Join-Path $Root 'server'
$EnvFile    = Join-Path $ServerDir '.env'

New-Item -ItemType Directory -Force -Path $Local, $Logs, $MinioData | Out-Null

# ---------- 工具函数 ----------
function Test-Port {
    param([int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Port {
    param([int]$Port, [string]$Name, [int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name 未能在 $TimeoutSec 秒内监听端口 $Port，请查看 $Logs 下的日志。"
}

function Import-DotEnv {
    param([string]$Path)
    Get-Content $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        Set-Item -Path "env:$key" -Value $value
    }
}

# ---------- 停止 ----------
if ($Stop) {
    if (Test-Path (Join-Path $PgData 'PG_VERSION')) {
        Write-Host '[停止] PostgreSQL ...'
        & (Join-Path $PgBin 'pg_ctl.exe') -D $PgData stop -m fast | Out-Null
    }
    $minioPidFile = Join-Path $Logs 'minio.pid'
    if (Test-Path $minioPidFile) {
        Write-Host '[停止] MinIO ...'
        Stop-Process -Id ([int](Get-Content $minioPidFile)) -Force -ErrorAction SilentlyContinue
        Remove-Item $minioPidFile -Force -ErrorAction SilentlyContinue
    }
    # 停止占用 3000 端口的后端进程
    Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Write-Host '[完成] 已停止。'
    return
}

# ---------- 1. 环境变量 ----------
if (-not (Test-Path $EnvFile)) {
    Write-Host '[初始化] 未找到 server\.env，正在从 .env.example 生成 ...'
    $secret = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
    $content = Get-Content (Join-Path $ServerDir '.env.example') -Raw -Encoding UTF8
    $content = $content -replace '(?m)^JWT_SECRET=.*$', "JWT_SECRET=$secret"
    if ($content -notmatch '(?m)^S3_REGION=') { $content += "`r`nS3_REGION=us-east-1" }
    if ($content -notmatch '(?m)^S3_FORCE_PATH_STYLE=') { $content += "`r`nS3_FORCE_PATH_STYLE=true" }
    [IO.File]::WriteAllText($EnvFile, $content, (New-Object System.Text.UTF8Encoding($false)))
}
Import-DotEnv $EnvFile

# ---------- 2. npm 依赖 ----------
if (-not (Test-Path (Join-Path $ServerDir 'node_modules'))) {
    Write-Host '[安装] server 依赖（npm install）...'
    Push-Location $ServerDir
    try { npm install --no-audit --no-fund } finally { Pop-Location }
}
# Prisma Client 缺失，或 schema 比已生成的 Client 更新时，都需要重新生成
$PrismaClientEntry = Join-Path $ServerDir 'node_modules\.prisma\client\index.js'
$PrismaSchemaFile  = Join-Path $ServerDir 'prisma\schema.prisma'
$needGenerate = -not (Test-Path $PrismaClientEntry)
if (-not $needGenerate -and
    (Get-Item $PrismaSchemaFile).LastWriteTime -gt (Get-Item $PrismaClientEntry).LastWriteTime) {
    $needGenerate = $true
}
if ($needGenerate) {
    Write-Host '[安装] 生成 Prisma Client ...'
    Push-Location $ServerDir
    try {
        npx prisma generate
        if ($LASTEXITCODE -ne 0) { throw 'prisma generate 失败，请检查是否有进程占用 Prisma 引擎文件' }
    } finally { Pop-Location }
}

# ---------- 3. PostgreSQL ----------
if (-not (Test-Path (Join-Path $PgBin 'pg_ctl.exe'))) {
    throw "未找到 PostgreSQL（$PgBin），请先运行安装步骤或恢复 .local 目录。"
}
& (Join-Path $PgBin 'pg_ctl.exe') -D $PgData status *> $null
if ($LASTEXITCODE -ne 0) {
    if (-not (Test-Path (Join-Path $PgData 'PG_VERSION'))) {
        Write-Host '[初始化] 初始化 PostgreSQL 数据目录 ...'
        $pwFile = Join-Path $Local 'initdb-password.txt'
        [IO.File]::WriteAllText($pwFile, 'photosync', (New-Object System.Text.ASCIIEncoding))
        try {
            & (Join-Path $PgBin 'initdb.exe') -D $PgData -U photosync -A scram-sha-256 --pwfile=$pwFile -E UTF8 --locale=C
            if ($LASTEXITCODE -ne 0) { throw 'initdb 执行失败' }
        } finally {
            Remove-Item $pwFile -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host '[启动] PostgreSQL ...'
    & (Join-Path $PgBin 'pg_ctl.exe') -D $PgData -l (Join-Path $Logs 'postgres.log') -o '-p 5432' -w start
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 启动失败，见 $Logs\postgres.log" }
}
Wait-Port -Port 5432 -Name 'PostgreSQL'

# 确保数据库存在
$env:PGPASSWORD = 'photosync'
$dbExists = & (Join-Path $PgBin 'psql.exe') -U photosync -h 127.0.0.1 -p 5432 -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'photosync'"
if (($dbExists | Out-String).Trim() -ne '1') {
    Write-Host '[初始化] 创建数据库 photosync ...'
    & (Join-Path $PgBin 'createdb.exe') -U photosync -h 127.0.0.1 -p 5432 -E UTF8 photosync
}
Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue

# ---------- 4. MinIO ----------
if (-not (Test-Path $MinioExe)) {
    throw "未找到 MinIO（$MinioExe），请先运行安装步骤或恢复 .local 目录。"
}
if (-not (Test-Port 9000)) {
    Write-Host '[启动] MinIO ...'
    if (-not $env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER = 'minioadmin' }
    if (-not $env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD = 'minioadmin' }
    $minioProc = Start-Process -FilePath $MinioExe `
        -ArgumentList @('server', $MinioData, '--address', ':9000', '--console-address', ':9001') `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Logs 'minio.log') `
        -RedirectStandardError (Join-Path $Logs 'minio.err.log')
    Set-Content -Path (Join-Path $Logs 'minio.pid') -Value $minioProc.Id
}
Wait-Port -Port 9000 -Name 'MinIO'

# ---------- 5. 同步数据库结构 ----------
Write-Host '[初始化] 同步数据库结构（prisma db push）...'
Push-Location $ServerDir
try {
    npx prisma db push --skip-generate
    if ($LASTEXITCODE -ne 0) { throw 'prisma db push 失败' }
} finally {
    Pop-Location
}

# ---------- 6. 启动后端 ----------
Write-Host ''
Write-Host '=============================================='
Write-Host ' PhotoSync 后端已就绪'
Write-Host "   API:     http://localhost:$($env:PORT)/api/v1/health"
Write-Host "   Docs:    http://localhost:$($env:PORT)/docs"
Write-Host '   MinIO:   http://localhost:9001 (minioadmin / minioadmin)'
Write-Host '   Ctrl+C 停止后端；停止本地依赖: .\start-backend.ps1 -Stop'
Write-Host '=============================================='
Write-Host ''

Push-Location $ServerDir
try {
    npm run dev
} finally {
    Pop-Location
}
