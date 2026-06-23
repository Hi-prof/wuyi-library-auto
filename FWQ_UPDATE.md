# fwq 服务端一键更新

先把新包上传到服务器目录：

```text
/www/wwwroot/lib.xuhb.top/prevent_auto-0.1.0.tar.gz
```

然后在服务器终端执行这一条命令：

```bash
bash -lc 'set -euo pipefail
cd /www/wwwroot/lib.xuhb.top
SERVICE=wuyi-prevent-auto
VERSION=0.1.1
TS=$(date +%Y%m%d-%H%M%S)
mkdir -p "_backup/fwq-$TS" _deploy
cp -a library-fwq/src/prevent_auto "_backup/fwq-$TS/prevent_auto-src"
cp -a library-fwq/data "_backup/fwq-$TS/data" 2>/dev/null || true
cp -a library-fwq/runtime "_backup/fwq-$TS/runtime" 2>/dev/null || true
rm -rf "_deploy/prevent_auto-$VERSION"
tar -xzf "prevent_auto-$VERSION.tar.gz" -C _deploy
test -d "_deploy/prevent_auto-$VERSION/src/prevent_auto"
rm -rf library-fwq/src/prevent_auto
cp -a "_deploy/prevent_auto-$VERSION/src/prevent_auto" library-fwq/src/prevent_auto
systemctl restart "$SERVICE"
for _ in $(seq 1 30); do ss -ltn "sport = :5000" | grep -q LISTEN && break; sleep 0.5; done
systemctl status "$SERVICE" --no-pager -l
curl -I http://127.0.0.1:5000/
curl -I https://lib.xuhb.top/
echo "更新完成，备份目录：_backup/fwq-$TS"'

```

`curl` 返回 `303 See Other` 并跳转到 `/login?next=%2F` 是正常结果。

`systemctl restart` 之后会轮询最多 15 秒，等到 `127.0.0.1:5000` 真正监听后再 `curl`，避免抢跑误报连接失败。如果 15 秒后还没监听，说明服务启动出错，先看 `systemctl status` 与 `journalctl -u wuyi-prevent-auto -n 100 --no-pager`。

下次版本号变了，只改命令里的：

```bash
VERSION=0.1.0
```

## 回滚

如果更新后服务起不来，把 `TS` 改成上面输出的备份时间戳：

```bash
bash -lc 'set -euo pipefail; cd /www/wwwroot/lib.xuhb.top; SERVICE=wuyi-prevent-auto; TS=刚才的时间戳; rm -rf library-fwq/src/prevent_auto; cp -a "_backup/fwq-$TS/prevent_auto-src" library-fwq/src/prevent_auto; systemctl restart "$SERVICE"; journalctl -u "$SERVICE" -n 100 --no-pager; curl -I http://127.0.0.1:5000/; curl -I https://lib.xuhb.top/'
```

## 排查

```bash
systemctl status wuyi-prevent-auto --no-pager -l
journalctl -u wuyi-prevent-auto -n 100 --no-pager
```
