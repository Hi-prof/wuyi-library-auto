# account-tester

批量测试图书馆账号能否正常登录的小工具。账号和密码都使用学号本身。

脚本完全基于 Python 标准库，不依赖 `library-window` 包，可以独立运行。
登录接口、字段、请求顺序与 `library-window` 中已经验证过的实现保持一致。

## 用法

准备一个学号文件（一行一个，`#` 开头是注释）：

```text
# accounts.txt
20231121130
20231121131
```

运行：

```powershell
# 顺序执行，默认每个账号之间等待 0.5 秒
python .\account-tester\check_accounts.py --input .\account-tester\accounts.txt

# 也可以直接在命令行里传入学号
python .\account-tester\check_accounts.py --ids 20231121130 20231121131

# 并发 4 路加速（注意可能触发风控，按需调整）
python .\account-tester\check_accounts.py --input .\account-tester\accounts.txt --workers 4

# 只想确认输入文件是否被正确解析，不发起任何请求
python .\account-tester\check_accounts.py --input .\account-tester\accounts.txt --dry-run
```

可用参数（`--help` 查看完整列表）：

- `--input` / `-i`：学号文件路径。
- `--ids`：直接传入若干学号，与 `--input` 二选一。
- `--login-url`：默认 `https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list`。
- `--workers`：并发线程数，默认 1。
- `--delay`：顺序模式下每次登录之间的等待秒数，默认 0.5。
- `--timeout`：单次请求超时秒数，默认 20。
- `--output-dir`：结果输出目录，默认 `account-tester/results/<时间戳>/`。

## 输出

结果目录下会生成：

- `success.txt`：登录成功的学号列表，一行一个。
- `failed.csv`：登录失败的学号、错误信息、耗时。
- `summary.json`：完整结构化结果，便于二次处理。

## 注意

- `accounts.txt`、`results/` 已经在本目录的 `.gitignore` 中忽略，不会被提交。
- 脚本只做登录态校验，不会预约座位、不会修改账号任何信息。
- 高并发可能触发对方风控，建议从 `--workers 1` 开始试。
