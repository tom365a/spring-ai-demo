# 界面改造：方向 A「控制台蓝」+ 思源黑体

日期：2026-09-11。目标：把演示界面做成中国企业客户预期里「可采购的产品」的样子。
**信息架构、字段、按钮语义、确认与发布流程全部未改动**，本轮只换视觉语言与字体，外加四处由此暴露的实现缺陷修复。

设计画布：<https://claude.ai/code/artifact/9fb37ba8-779c-43e2-9300-f458a5ca1b69>
「交付」页为现状 / 交付后对照、客服对话页与写操作确认、窄屏 390px、设计规范；「未采用的方向」页保留方向 B / C 的原始方向稿备查。

## 1. 改造前的问题（实测定位）

| # | 问题 | 根因 |
| --- | --- | --- |
| 1 | 一行标题里两种字体：「Agent」是衬线、「管理」是黑体 | `admin.css` 的 `.brand` 写死 `font-family: Georgia, "Times New Roman", serif`。Georgia 没有中文字形，中文逐字回退到系统黑体 |
| 2 | 深青绿渐变 + 薄荷绿主色，观感偏个人项目 | 底色 `#0f1c24 → #16303a` 叠青绿光晕，主色 `#2ec4b6` |
| 3 | 全站胶囊按钮 + 低对比正文 | 按钮一律 `border-radius: 999px`；辅助文字 `#9bb4b8` 对白底仅 3.2:1 |

## 2. 字体：思源黑体（Noto Sans SC）

选它的唯一理由是**授权干净**：SIL Open Font License 1.1，明确允许商用、嵌入与再分发。
字体文件自带的 `name` 表 nameID 14 就指向 `http://scripts.sil.org/OFL`，许可证全文随包放在 `fonts/noto-sans-sc/OFL.txt`。

对照之下，原来的字体栈里 **苹方是苹果的、微软雅黑是方正为微软设计的**，都不是免费商用字体；中易黑体（SimHei）同样有版权，中易起诉暴雪、方正起诉宝洁都是真实案例。
仅在 CSS 里 `font-family` 引用本机已装字体通常不构成分发，但既然要做成对外交付的产品，索性让渲染结果完全不依赖它们。

落地方式：

- **自托管，运行时不请求任何外部字体服务**（Google Fonts 在境内不可靠）。
- 可变字体，`wght` 轴 100–900，一份文件覆盖 400/500/600 三个字重。
- 按 `unicode-range` 切成 **101 个 woff2 分片**，浏览器只下载页面命中的那几片。

| 指标 | 数值 |
| --- | --- |
| 分片总数 / 磁盘占用 | 101 个 / 4.31 MB |
| 字体 CSS | 108.2 KB → **gzip 后 30.3 KB** |
| 单页实际下载 | Admin 首屏命中 **15–16 片，约 861 KB**；此后按 `immutable` 缓存一年 |
| 分片大小 | 最大 75 KB，中位数 47 KB |

字体栈收敛为 `"Noto Sans SC", system-ui, -apple-system, BlinkMacSystemFont, sans-serif`，中英文同源。

## 3. 设计令牌

新增 [`static/theme.css`](../../apps/java-gateway/src/main/resources/static/theme.css) 作为唯一取值来源，`admin.css` / `resource.css` / `index.html` 只消费变量。
**换视觉方向 = 改这一份取值**，不必再动组件样式。

主色 `#0b62d6`、左侧导航 `#1d2129`、工作区 `#f2f3f5`、圆角 6px、控件高 32px。

色值不是照抄设计稿，而是按对比度反推的：正文级文字对其实际底色**全部 ≥ 4.5:1**。
比如原稿里的 `#00b42a` 绿标签在 `#e8ffea` 上只有 2.6:1，改用 `#087a20` 后是 5.2:1；主色由 `#1677ff`（白字 4.1:1，不达标）压深到 `#0b62d6`（5.6:1）。

## 4. 改动清单

| 文件 | 改动 |
| --- | --- |
| `static/theme.css` | 新增，设计令牌 |
| `static/fonts/noto-sans-sc.css` + `fonts/noto-sans-sc/` | 新增，101 个 woff2 + OFL 许可证 |
| `static/admin.css` | 重写。左侧全高深色导航 + 右侧顶栏 + 内容区栅格；表单、按钮、表格、标签页、弹窗、轻提示全部换令牌 |
| `static/resource.css` | 重写。资源卡片、详情弹窗、粘性动作条、窄屏折叠 |
| `static/admin.html` | 顶部品牌块与横向胶囊导航改为 `<aside class="sidebar">`；正文包进 `<main class="content">`。**`.nav button` 与 `#runtimeChip`/`#runtimeHint` 保持原样**，脚本无需改动 |
| `static/index.html` | 样式换令牌；写操作确认卡片由 JSON 转储改为结构化字段 |
| `config/WebConfig.java` | 静态资源缓存策略 |
| `resources/application.yml` | 开启 gzip |

### 顺带修掉的四个实现缺陷

1. **窄屏布局完全不生效**：`.shell > main`（特指度 0,1,1）压过媒体查询里的 `.content`（0,1,0），侧栏在 390px 下不折叠。改为 `.shell > .content`（0,2,0）。
2. **内容区撑破视口**：`.content` 的隐式栅格列是 `auto`，会被宽表格拉宽到 988px。改为 `grid-template-columns: minmax(0, 1fr)`，宽内容改在自己的容器里横向滚动。
3. **改版后可能看到旧界面**：静态资源原先没有缓存头。现在 HTML/CSS/JS 走 `no-cache, must-revalidate`，内容哈希命名的字体走 `max-age=31536000, immutable`。
4. **侧栏底部长 token 溢出**：`APP_AGENT_CONFIG_ENABLED=true…` 无断词点，撑出 208px 的导航栏。加 `overflow-wrap: anywhere`。

另外开启 gzip：应用 CSS+JS 由 100.4 KB 降到 **30.3 KB**（3.3×），字体 CSS 由 108.2 KB 降到 30.4 KB（3.6×）。woff2 自带 Brotli，不再二次压缩。

> 更正：本文先前写的「99 KB → 4 KB」是错的。那个 4 KB 是浏览器带缓存访问时 `304 Not Modified` 的响应头体积，不是压缩后的正文——因为同一次测量里字体的 `transferSize` 也是 0。上面的数字是对实际文件逐个 gzip 实算的结果。

### 客服端确认卡片

原来直接 `JSON.stringify(payload)` 平铺，演示时像调试输出。改为「操作 / 工具 / 参数 / 有效期」四行 + 红色「确认后立即执行，不可撤销」提示，原始 payload 收进折叠区备查。

字段按接口**实际返回**渲染：参数值服务端返回的就是 `[已脱敏]`，所以卡片只列参数名并注明「值已在服务端脱敏」，不编造订单号。

## 5. 验收结果

改造后全量重跑，见 [evidence/ui-redesign.json](evidence/ui-redesign.json)：

| 检查 | 结果 |
| --- | --- |
| Java 全量测试（12 个类） | **75/75 通过** |
| 独立整机 `qa-resource-http.mjs` | **22/22 通过** |
| 旧功能回归 `qa-legacy-regression.mjs` | **8/8 通过** |
| 根整机集成 `resource-integration-check.mjs` | **7/7 通过** |
| 对比度（5 个一级页 + 全部详情弹窗 + 客服页，桌面与 390px） | **0 处不达标** |
| 被压扁控件 / 页面级横向溢出 | **0 / 0** |
| 字体实际生效 | 计算样式为 `Noto Sans SC`，渲染宽度与微软雅黑实测不同（387.81 vs 395.98 px），排除静默回退 |
| 写工具确认全链路 | 浏览器实操：待确认 → 确认执行 → 真实返回「订单已取消」 |

对比度用页面内脚本按 WCAG 相对亮度公式逐个文本节点实算，不是目测。

## 6. 未做与遗留

- 只做了方向 A。方向 B / C 的取值没有落进 `theme.css`，需要时再补一份令牌覆盖。
- 客服页顶部那条「若能看到这条提示，前端已正常加载」是早期自检提示，已弱化为浅灰小字，但**没有删除**——它是否还需要保留，由你决定。
- 右侧「运行详情」面板仍是 JSON 原文。对技术观众是加分项，对纯业务观众偏工程味；本轮没动它的信息结构。
- 未部署到 8080 用户应用，全部验证在隔离端口 18080 完成。
