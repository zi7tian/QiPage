# 测试样本

用 `./scripts/new-fixtures.sh` 生成到 `fixtures/generated/`（该目录不提交）。

所有正文都是项目自己造的，不含任何用户书籍，因此可以安全地随仓库分发或用于回归。

## 覆盖的场景

| 样本 | 验证什么 |
| --- | --- |
| `epub3-basic.epub` / `epub2-basic.epub` | 可重排 EPUB 3 与 EPUB 2 的基本解析、目录与正文 |
| `script-remote.epub` | 书内脚本不执行、远程图片/iframe/链接被拦截 |
| `fixed-layout.epub` | 固定版式被明确拒绝并给出提示 |
| `zip-traversal.epub` | ZIP 路径越界被拒绝 |
| `p2-long.epub` | 长章节、段落锚点、章内跳转 |
| `p2-cover.epub` | 本机封面缩略图（`properties="cover-image"`） |
| `p4-layout.epub` / `p4-layout.txt` | 分页与排版：短章独立成页、章末留白、行高与段距 |
| `many-chapters.txt` | 300 章长目录，验证快速滚动条 |
| `utf8-small.txt` / `gb18030-small.txt` | UTF-8 与 GB18030 编码识别 |
| `utf8-5-MiB.txt` / `utf8-100-MiB.txt` | 大文件索引与低内存分页（需 `--include-large-txt`） |

## 约定

- 书内脚本样本的正文标记初始为 `SCRIPT_NOT_EXECUTED`；若脚本被执行则会被改成
  `SCRIPT_EXECUTED`，用来区分「书内脚本确实被禁用」与「只是断了网」。
- 远程资源一律使用不可解析的 `.invalid` 测试域名，测试期间不会向任何第三方发送数据。
- 样本**没有**声称通过 EPUBCheck。它们是功能与对抗场景样本；正式兼容性回归仍需要
  标准认证样本与获得授权的真实书籍。

## 生成

```bash
./scripts/new-fixtures.sh                      # 常规样本
./scripts/new-fixtures.sh --include-large-txt  # 追加 5 / 100 MiB 压力样本
```

生成逻辑见 `scripts/gen_fixtures.py`。样本必须可逐字节复现，因此用 Python 的
`zipfile` 精确控制 EPUB 的 `mimetype` 必须是首个且不压缩的条目。
