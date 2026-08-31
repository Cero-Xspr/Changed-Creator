# Changed Creator 使用说明

**Changed Creator** 是一个 Forge 1.20.1 模组，让你**不需要写代码**就能自定义《Changed: Minecraft》的胶兽形态——新建形态、改外观、画荧光、实时预览，全部通过网页编辑器完成。

---

## 1. 安装

### 文件清单（三个都要）

```
mods/
  changedcreator-1.0.0.jar          ← 本模组
  Changed-m1.20.1-v0.15.7-all.jar   ← Changed 本体（自带 mixinextras，无需额外装）
config/
  changedcreator/                   ← 你的自定义数据（可留空，首次启动自动建目录）
```

### 环境要求

- Minecraft **1.20.1** + **Forge 47.4.x**（Windows / Linux 通用）
- 首次启动会自动创建 `config/changedcreator/forms/` 目录
- 建议在 `config/changed-common.toml` 里设 `downloadPatreonContent = false`（避免启动时联网卡死）

## 2. 快速开始

1. 启动游戏 → 主菜单左下角（语言按钮旁）点**胶兽编辑器**方块按钮（或暂停菜单同款按钮）
2. 点「在浏览器打开 WebUI」（浏览器访问 `http://127.0.0.1:28654`）
3. WebUI 左侧选「原版示例」参考，或点「＋ 新建形态」
4. 填写：`id`（小写字母/数字/下划线）、`base_entity`（**从下拉选已注册胶兽实体**）、能力/属性
5. 点「保存」→ 点「**热注册（免重启）**」→ 游戏内 `/transfur @s changedcreator:<你的id>` 变身

> 改 `id`/`base_entity`/能力/属性后**必须重新热注册或重启**才生效（注册表冻结）；
> 改 **tint / 贴图** 保存后约 2 秒自动生效。

## 3. 编辑器功能

### 3D 预览
- 拖动旋转视角；**箭头键**在预览窗口上时移动视角（上/下=垂直，左/右=水平），**U** 还原
- 点击方块选中（黄色线框）；**再点同一位置**穿透选内层方块（大包小时逐层向内）
- 按 **E** 进入编辑模式：选中方块不透明、其余半透明，画笔/填充直接作用到该方块贴图区域
- 悬停贴图视窗：模型上所有引用该像素的面高亮 + 品红像素标记（遮挡时半透明）

### 贴图编辑
- **画笔 / 填充桶 / 取色器**（方形像素笔刷，RGBA 色盘 + HEX 输入 + 色轮）
- 滚轮缩放贴图视窗，箭头键平移；**撤销 / 重做**（Ctrl+Z / Ctrl+Y）
- **发光层**：点「发光层」切到第二层画布（原贴图半透明对照），用任意颜色画=荧光区域，保存后游戏内发光（发光颜色=像素颜色）
- 导入/导出贴图、导出形态文件（可放到 `config/changedcreator/imports/` 由游戏内界面导入）

### 形态管理
- 列表右侧 **✕** 删除形态（热移除注册表 + 删配置文件）
- 「热注册（免重启）」：运行时注册/更新形态，服务器常开也能用（已连接玩家需重进世界可见）

## 4. 配置结构

```
config/changedcreator/
  forms/<id>.json        ← 形态定义（id/base_entity/transfur_mode/abilities/properties）
  appearance.json        ← 外观（texture 贴图 id、tint 主体色）
  textures/<id>.png      ← 编辑器导出的本体贴图
  textures/<id>_emissive.png  ← 编辑器导出的荧光贴图
  models/                ← 模型部件缓存（编辑器自动生成，可删）
  imports/               ← 游戏内导入导出文件的暂存目录
```

### 形态 JSON 示例

```json
{
  "id": "my_wolf",
  "base_entity": "changed:white_wolf_male",
  "transfur_mode": "replication",
  "abilities": ["changed:grab_entity"],
  "properties": {
    "gills": true,
    "nightVision": true
  }
}
```

可选属性：`gills` / `canClimb` / `nightVision` / `reducedFall` / `glide` / `doubleJump` / `extraJumps` / `quadrupedal` / `noLegs` / `disableItems` / `holdItemsInMouth` / `cameraZOffset` / `sound`

### 外观 JSON 示例

```json
{
  "changedcreator:my_wolf": {
    "tint": "#ff3333",
    "texture": "changedcreator:textures/entity/red_wolf.png"
  }
}
```

- `tint`：本体 latex 色 + 背包/技能轮盘 UI 色
- `texture`：贴图 id（编辑器导出 PNG 后无需手动填，自动生效）

## 5. 联机（多人）

- **形态（变体）**：服务端注册 → 自动同步给客户端；服务端和每个客户端都要装本模组 + Changed
- **热注册**：服务端点一次 + 每个客户端各点一次；已连接玩家**重进世界**后可见新形态
- **外观（tint/贴图/荧光）**：客户端渲染配置，各端自行设置（或直接分发相同 `config/changedcreator/`）
- **没经过验证！**

## 6. 常见问题

| 问题 | 处理 |
|---|---|
| 变身报"意外错误" | 检查 `abilities` 是否填了不存在的 id（编辑器会提示可用能力，填错会直接拦截） |
| 新形态保存后游戏里没有 | 需要「热注册」或重启；确认 `base_entity` 从下拉选的有效实体 |
| 启动卡死 | `changed-common.toml` 设 `downloadPatreonContent = false` |
| 编辑器打不开 | 检查日志 `Editor WebUI available at http://127.0.0.1:28654`；端口被占会自动换随机端口 |
| 荧光不显示 | 确认保存时画了发光层（编辑器导出 `_emissive.png`）且已重启/热注册 |
