# library-fwq

`library-fwq` 是一个独立于 Windows 客户端页面的守护服务，用来持续监测多个账号当天的预约与签到状态。

下面命令默认在仓库根目录执行。

## 功能

- 多账号管理
- 按短间隔持续检查当天预约状态，在签到截止前按账号设置的提前分钟数触发保号
- 未发现下一时段预约时，自动取消当前预约并执行保号
- 保号使用最近识别到的预约座位；没有识别记录时回退到当前预约座位
- 重约窗口固定为下一个整点到当天 22:00
- 提供仪表盘和账号管理页面，账号详情与日志收纳在账号管理弹窗中

## 安装

建议使用 `uv` 创建隔离环境，并安装测试依赖：

```powershell
cd library-fwq
uv sync --extra test
```

服务端运行时还需要能导入 Windows 客户端里的接口代码。直接从仓库根目录启动时，可以通过 `fwq-qd.bat` 自动注入两个 Python 子项目的 `src` 路径。

## 启动

```powershell
.\fwq-qd.bat
```

Linux:

```bash
export PYTHONPATH=$PWD/library-window/src:$PWD/library-fwq/src
python -m prevent_auto.main
```

默认监听 `127.0.0.1:5000`，后台巡检默认每 60 秒运行一次，重约任务默认每 15 秒轮询一次。

## 测试

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
uv run --extra test python -m pytest
```

默认登录页账号和密码仅用于本地监听。部署到公网或局域网地址时，必须通过环境变量
`PREVENT_AUTO_AUTH_USERNAME`、`PREVENT_AUTO_AUTH_PASSWORD` 和
`PREVENT_AUTO_SESSION_SECRET` 覆盖认证配置，否则服务会拒绝启动。

## 目录

- `data/prevent_auto.db`：SQLite 数据库
- `runtime/`：登录态和运行时缓存
- `systemd/wuyi-prevent-auto.service`：Linux 服务文件

## 部署建议

1. 克隆整个仓库到 Linux 服务器。
2. 创建虚拟环境并安装主项目和子项目依赖。
3. 把 `library-fwq/systemd/wuyi-prevent-auto.service` 复制到 `/etc/systemd/system/`。
4. 根据实际路径调整 `WorkingDirectory`、`Environment` 和 `ExecStart`。
5. 执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable wuyi-prevent-auto
sudo systemctl start wuyi-prevent-auto
sudo systemctl status wuyi-prevent-auto
```

## 说明

- 登录态文件默认保存在 `library-fwq/runtime/`。
- Linux 下服务会把 `data/`、`runtime/` 和数据库文件权限限制为当前系统用户可读写。
- 巡检记录、动作日志和已结束重约任务默认保留 30 天，可通过 `PREVENT_AUTO_LOG_RETENTION_DAYS` 调整。
- 每天上午 8:10（上海时区）会自动对所有启用账号执行一次状态检测，可通过 `PREVENT_AUTO_DAILY_STATUS_REFRESH_TIME=HH:MM` 调整时间点。如果服务在该时间点之后启动，会立即补做当天的刷新。
- 当前版本只处理当天预约，不自动换抢其他座位。
- 如果学校接口规则变化，优先调整 `bridge_to_wuyi.py`。
