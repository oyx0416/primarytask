# 记

「记」是一个 Android 任务整理应用，使用 Kotlin、Jetpack Compose 和 Room。任务可手动录入，也可从粘贴文本或图片中提取草稿；确认后才会保存为本地任务。

## 数据处理流程

- 手动创建和用户确认保存的任务存放在设备本地 Room 数据库中。应用没有把任务列表同步到后端的功能。
- 粘贴文本默认由本地规则解析。只有在构建时设置 `AI_TASK_PARSER_URL`，文本才会发送到该 URL；这个可选接口由构建者自行配置。
- 图片识别会把用户主动选择的图片上传到 `AI_IMAGE_TASK_PARSER_URL` 指定的后端。未设置时，默认地址为 `https://api.primarytask.top/parse-image-task`。应用会在上传前缩放和 JPEG 压缩图片。
- 图片后端验证上传大小和图像像素后，将图片交给部署者通过环境变量配置的模型接口。`backend/.env.example` 默认启用模拟模式；若部署者配置真实模型和 API Key，图片会发送给该模型服务。模型服务商、其处理地点、留存和训练行为取决于部署配置，仓库无法确认。
- 使用图片服务时，应用会请求匿名账户。服务端生成账户 ID 和 Token；客户端提交的 `device_id`（旧版客户端仍可能发送）不会用于查找或刷新账户。已有有效 Token 可继续访问原账户；没有有效 Token 时，服务端创建新的匿名账户。
- 客户端使用 Android Keystore 加密保存 Token。后端数据库保存账户标识、Token 哈希、套餐和每日用量信息。源码未见把原始图片写入后端数据库或持久文件的逻辑；反向代理、云平台和模型服务商是否记录请求不由本项目源码决定。
- 应用内删除任务只删除本地任务。当前后端没有账户或服务端数据删除接口，也没有统一的服务端记录保留期限配置。

详细说明见[隐私政策](website/privacy/index.html)。该页面描述源码可确认的行为，不代替实际服务运营者根据其部署配置发布的正式告知。

## Android 配置与运行

需要 Android Studio、Android SDK 35 和 JDK 17。用 Android Studio 打开本仓库根目录，完成 Gradle Sync 后运行 `app`。

可通过 Gradle 属性配置解析端点：

```properties
AI_TASK_PARSER_URL=https://your-service.example/parse-text-task
AI_IMAGE_TASK_PARSER_URL=https://your-service.example/parse-image-task
```

`AI_TASK_PARSER_URL` 默认为空，此时粘贴文本走本地解析。图片端点默认为项目演示服务。Release 构建会拒绝非 HTTPS 的非空解析端点。不要把模型 API Key 或其他服务端密钥放进 Android 配置、源码或 APK。

## Backend

后端位于 `backend/`，基于 FastAPI。项目固定 Pillow 10.4，建议使用 Python 3.10–3.12：

```powershell
python -m venv backend/.venv
backend\.venv\Scripts\Activate.ps1
pip install -r backend/requirements.txt
Copy-Item backend/.env.example backend/.env
uvicorn main:app --app-dir backend --host 127.0.0.1 --port 8000
```

首次运行默认是模拟模型模式。部署真实模型时，在服务端 `.env` 或进程环境中设置 `JI_AI_MOCK=false`、模型端点、模型名称和 `JI_AI_MODEL_API_KEY`；密钥只应保存在服务端。生产环境始终要求认证。图片默认上限为 10 MiB、20,000,000 像素，并设置了图片验证、处理和模型请求超时；具体限制可通过环境变量调整。

上线前，部署者需要自行配置 HTTPS、可信反向代理、模型服务、日志与数据保留策略，并根据真实运营主体、联系渠道及第三方处理情况更新隐私告知。仓库不声明实际服务运营方、模型供应商或数据保留期限。

## 验证

Backend 回归测试（在 `backend` 目录运行）：

```powershell
python -m unittest discover -s tests -v
```

Android 单元测试（PowerShell）：

```powershell
.\gradlew.bat test
```

## License

本项目源码依据 [Apache License 2.0](LICENSE) 发布。第三方依赖、商标、站点备案图样和其他单独标注的素材仍受各自许可或权利约束。
