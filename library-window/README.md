# 武夷学院自习室自动预约工具

这是一个基于学校现有接口的自习室预约工具。

当前版本已经移除 Playwright 和浏览器助手，登录、入口解析、预约、签到、签退都优先走接口。只要账号配置里提供学号和密码，程序就能直接生成登录态，不再依赖手动扫码或弹出浏览器。

当前 Windows 客户端工程已经独立放在 `library-window/` 目录。下面命令默认在 `library-window/` 目录执行；如果你在仓库根目录操作，请先 `cd library-window`。

## 当前设计

- 使用学号密码直连学校登录接口，保存 `Cookie + currentUser` 登录态。
- 兼容单账号旧配置，也支持在一个 `config.json` 里维护多个账号，并在命令行或网页里切换。
- 用多个 `seat_urls` 维护入口页优先级，程序会按顺序尝试。
- 如果 `seat_urls` 填的是 `list` 入口页，程序会直接解析接口返回的真实 `searchSeats` 地址，不再模拟点击页面。
- 预约动作会优先走学校现有接口，先选中座位再提交，而不是直接确认系统推荐位。
- 签到动作会先读取“我的座位预约”列表，找到待签到记录后按学校前端同样的 `iBeacon minor` 规则做蓝牙匹配，命中后再调用签到接口。
- 签退动作会读取“我的座位预约”列表，找到当前在馆记录后直接调用学校现有的签退接口。
- 现在支持启动一个本地网页界面，手动选择日期、开始时间、使用时长、预约人数和具体座位。
- 网页界面支持立即签退，也支持按账号保存一次性“定时预约”“定时签到”“定时签退”任务。
- `web` 默认带守护进程和心跳检测，工作进程异常退出后会自动拉起，并可通过 `status` 查看运行状态。
- 双击 exe 时会自动隐藏控制台并驻留到系统托盘，右键托盘图标可以打开界面、查看状态、查看日志和退出服务。

## 适用前提

- 学校当前仍然允许通过账号密码接口登录。
- 自习室预约、签到、签退接口路径和参数规则没有发生结构性变化。
- 运行签到命令的这台 Windows 电脑需要有可用蓝牙，并且能扫描到自习室现场的 iBeacon 广播。

## 快速开始

1. 安装依赖：

   ```bash
   python -m pip install -e .
   ```

2. 生成配置文件：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json init-config
   ```

3. 把真实账号填进 `config.json`：

   ```json
   {
     "default_account": "主号",
     "max_attempts": 2,
     "retry_wait_seconds": 2,
     "accounts": [
       {
         "name": "主号",
         "student_id": "20231121130",
         "password": "你的密码",
         "login_url": "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
         "state_file": "runtime/auth-main.json",
         "seat_urls": [
           "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
         ],
         "preferred_seat_numbers": ["58", "57"]
       },
       {
         "name": "室友",
         "student_id": "20231121151",
         "password": "室友密码",
         "login_url": "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
         "state_file": "runtime/auth-roommate.json",
         "seat_urls": [
           "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
         ],
         "preferred_seat_numbers": []
       }
     ]
   }
   ```

   说明：

   - `student_id` 和 `password` 现在是必须项，`save-login` 会直接用它们换取登录态。
   - `login_url` 推荐直接填写 `https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list`。
   - `seat_urls` 可以填入口页，也可以直接填 `searchSeats` 地址；如果填入口页，程序会自动解析真实查询地址。
   - `preferred_seat_numbers` 可选，按优先级填写你想抢的座位号；如果留空，程序会从当前推荐阅览室里自动挑一个可用座位。
   - 多账号时请给每个账号单独的 `state_file`，不要共用同一个登录态文件。

4. 保存登录态：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json save-login
   ```

   多账号时请显式指定账号：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json save-login --account 主号
   python -m wuyi_seat_bot.cli --config config.json save-login --account 室友
   ```

5. 执行预约：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json reserve
   ```

   多账号时可以这样指定：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json reserve --account 主号
   ```

6. 启动本地网页选座界面：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json web
   ```

   常用参数：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json web --port 9000 --no-open
   python -m wuyi_seat_bot.cli --config config.json web --account 主号
   python -m wuyi_seat_bot.cli --config config.json web --no-guard
   python -m wuyi_seat_bot.cli --config config.json status
   ```

   网页界面目前支持：

   - 多账号切换
   - 自己选择入口页
   - 自己选择日期、开始时间、使用时长、预约人数
   - 点击座位图或按座位号列表选座
   - 单人预约直接提交
   - 多人预约先展示人数和选座结果，暂不直接提交
   - 立即签退
   - 添加一次性定时任务，并在网页中查看执行状态

7. 检查当前入口能否正常查询：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json inspect-status
   ```

   多账号或临时网址示例：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json inspect-status --account 主号
   python -m wuyi_seat_bot.cli --config config.json inspect-status --url "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
   ```

8. 执行签到：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json checkin
   ```

9. 执行签退：

   ```bash
   python -m wuyi_seat_bot.cli --config config.json checkout
   ```

10. 打包为 exe：

   先安装打包依赖：

   ```bash
   python -m pip install -e .[build]
   ```

   然后执行：

   ```bash
   python scripts/build_exe.py
   ```

    默认输出在仓库根目录的 `dist/exe/wuyi-seat-bot/wuyi-seat-bot.exe`，采用 `onedir` 模式。打包脚本会重建一个示例 `config.json`，并补齐 `runtime/logs`，这样整个目录拷到别的电脑后就能直接启动，再按需填写账号。如果你确实需要单文件，也可以这样：

   ```bash
   python scripts/build_exe.py --onefile
   ```

## 风险提醒

- 如果学校后续把登录改回扫码、验证码、短信校验或设备指纹校验，纯接口登录会失效。
- 蓝牙签到必须在预约房间附近执行；人不在现场时，命中不到对应 iBeacon 就不会放行签到。
- 如果学校系统做了严格的频率限制，过于频繁的重试可能触发风控。
- 当前版本优先保证“可读、可改、可调试”，还没有接入消息通知和 Windows 计划任务。
- exe 的自恢复主要覆盖“工作进程异常退出”和“后台调度线程意外死亡”两类问题；如果整台电脑休眠、断电或被系统强制结束进程，仍然需要依赖你重新启动程序。
